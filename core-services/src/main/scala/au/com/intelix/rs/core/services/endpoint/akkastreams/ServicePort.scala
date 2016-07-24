/*
 * Copyright 2014-16 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package au.com.intelix.rs.core.services.endpoint.akkastreams

import akka.actor.ActorRefFactory
import akka.dispatch.Futures
import akka.pattern.Patterns
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream._
import akka.util.ByteString
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.config.RootConfig
import au.com.intelix.evt.{CommonEvt, EvtContext, InfoE, WarningE}
import au.com.intelix.rs.core.services.Messages._
import au.com.intelix.rs.core.services.internal.{SignalPort, StreamAggregatorActor}


object ServicePort {

  val EvtSourceId = "ServicePort"

  case object EvtFlowStarting extends InfoE

  case object EvtFlowStopping extends InfoE

  case object EvtSignalRequest extends InfoE

  case object EvtSignalResponse extends InfoE

  case object EvtSignalExpired extends InfoE

  case object EvtSignalFailure extends WarningE

  def buildFlow(tokenId: String)(implicit context: ActorRefFactory, nodeCfg: RootConfig) = Flow.fromGraph(GraphDSL.create() { implicit b =>

    import GraphDSL.Implicits._

    implicit val ec = context.dispatcher

    val evtCtx = EvtContext(EvtSourceId, nodeCfg.config)

    val signalParallelism = nodeCfg.asInt("signal-parallelism", 100)

    val aggregator = context.actorOf(StreamAggregatorActor.props(tokenId), s"aggregator-$tokenId")
    val signalPort = context.actorOf(SignalPort.props, s"signal-port-$tokenId")



    class MessageRouter extends GraphStage[UniformFanOutShape[ServiceInbound, ServiceInbound]] {
      override val shape: UniformFanOutShape[ServiceInbound, ServiceInbound] = new UniformFanOutShape(2)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
        val pendingSignals = scala.collection.mutable.Queue[ServiceInbound]()
        val pendingSubscriptions = scala.collection.mutable.Queue[ServiceInbound]()

        override def preStart(): Unit = {
          evtCtx.raise(EvtFlowStarting, 'token -> tokenId)
          pull(shape.in)
        }



        setHandler(shape.in, new InHandler {
          override def onPush(): Unit = {
            grab(shape.in) match {
              case x: Signal =>
                pendingSignals.enqueue(x)
                doPushSignal()
              case x: OpenSubscription =>
                pendingSubscriptions.enqueue(x)
                doPushSubscription()
              case x: CloseSubscription =>
                pendingSubscriptions.enqueue(x)
                doPushSubscription()
              case _ =>
            }
            doPull()
          }
        })
        setHandler(shape.out(0), new OutHandler {
          override def onPull(): Unit = doPushSubscription()
        })
        setHandler(shape.out(1), new OutHandler {
          override def onPull(): Unit = doPushSignal()
        })

        override def postStop(): Unit = {
          evtCtx.raise(EvtFlowStopping, 'token -> tokenId)
          context.stop(aggregator)
          context.stop(signalPort)
          super.postStop()
        }

        def doPull() = if (canPull) pull(shape.in)

        def canPull = !hasBeenPulled(shape.in) && hasCapacity

        def hasCapacity = pendingSignals.size < 64 && pendingSubscriptions.size < 64

        def doPushSignal() = if (canPushSignal) {
          push(shape.out(1), pendingSignals.dequeue())
          doPull()
        }

        def canPushSignal = isAvailable(shape.out(1)) && pendingSignals.nonEmpty

        def doPushSubscription() = if (canPushSubscription) {
          push(shape.out(0), pendingSubscriptions.dequeue())
          doPull()
        }

        def canPushSubscription = isAvailable(shape.out(0)) && pendingSubscriptions.nonEmpty
      }

    }

    val publisher = Source.actorPublisher(ServicePortStreamSource.props(aggregator, tokenId))

    val requestSink = b.add(Sink.actorSubscriber(ServicePortSubscriptionRequestSinkSubscriber.props(aggregator, tokenId)))

    val signalExecStage = b.add(Flow[ServiceInbound].mapAsyncUnordered[ServiceOutbound](signalParallelism) {
      case m: Signal =>
        val start = System.nanoTime()
        val expiredIn = m.expireAt - System.currentTimeMillis()
        evtCtx.raise(EvtSignalRequest, 'correlation -> m.correlationId, 'expiry -> m.expireAt, 'expiredin -> expiredIn, 'subj -> m.subj, 'group -> m.orderingGroup)
        expiredIn match {
          case expireInMillis if expireInMillis > 0 =>
            Patterns.ask(signalPort, m, expireInMillis).recover {
              case t =>
                evtCtx.raise(EvtSignalExpired, 'correlation -> m.correlationId, 'subj -> m.subj)
                SignalAckFailed(m.correlationId, m.subj, None)
            }.map {
              case t: SignalAckOk =>
                val diff = System.nanoTime() - start
                evtCtx.raise(EvtSignalResponse, 'success -> true, 'correlation -> m.correlationId, 'subj -> m.subj, 'response -> String.valueOf(t), 'ms -> ((diff / 1000).toDouble / 1000))
                t
              case t: SignalAckFailed =>
                val diff = System.nanoTime() - start
                evtCtx.raise(EvtSignalResponse, 'success -> false, 'correlation -> m.correlationId, 'subj -> m.subj, 'response -> String.valueOf(t), 'ms -> ((diff / 1000).toDouble / 1000))
                t
              case t =>
                evtCtx.raise(EvtSignalFailure, 'correlation -> m.correlationId, 'subj -> m.subj, 'response -> String.valueOf(t))
                SignalAckFailed(m.correlationId, m.subj, None)
            }
          case _ => Futures.successful(SignalAckFailed(m.correlationId, m.subj, None))
        }
      case m: CloseSubscription =>
        evtCtx.raise(CommonEvt.EvtInvalid, 'type -> m.getClass, 'reason -> "Signal expected")
        Futures.successful(SignalAckFailed(None, m.subj, None))
      case m: OpenSubscription =>
        evtCtx.raise(CommonEvt.EvtInvalid, 'type -> m.getClass, 'reason -> "Signal expected")
        Futures.successful(SignalAckFailed(None, m.subj, None))
    })


    val merge = b.add(MergePreferred[ServiceOutbound](1))

    val publisherSource = b.add(publisher)

    val router = b.add(new MessageRouter)

    router.out(0) ~> requestSink // stream subscriptions
    router.out(1) ~> signalExecStage ~> merge.preferred // signals
    merge <~ publisherSource

    FlowShape(router.in, merge.out)

  })

}