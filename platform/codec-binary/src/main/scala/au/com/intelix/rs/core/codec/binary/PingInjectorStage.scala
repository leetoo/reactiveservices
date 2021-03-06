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
package au.com.intelix.rs.core.codec.binary

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import au.com.intelix.config.ConfigOps.wrap
import au.com.intelix.config.RootConfig
import au.com.intelix.evt.{EvtContext, TraceE}
import au.com.intelix.rs.core.codec.binary.BinaryProtocolMessages.{BinaryDialectInbound, BinaryDialectOutbound, BinaryDialectPing, BinaryDialectPong}
import au.com.intelix.rs.core.config.ServiceConfig

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

object PingInjectorStage {

  object Evt {
    case object ServerClientPing extends TraceE
  }

}


class PingInjectorStage extends BinaryDialectStageBuilder {


  private class PingInjector(serviceCfg: ServiceConfig, nodeCfg: RootConfig, sessionId: String) extends GraphStage[BidiShape[BinaryDialectInbound, BinaryDialectInbound, BinaryDialectOutbound, BinaryDialectOutbound]] {
    val interval = serviceCfg.asFiniteDuration("ping.interval", 30 seconds)
    val outBuffer = serviceCfg.asInt("ping.out-buffer-msg", defaultValue = 8)
    val inBuffer = serviceCfg.asInt("ping.in-buffer-msg", defaultValue = 8)
    val in1: Inlet[BinaryDialectInbound] = Inlet("ServiceBoundIn")
    val out1: Outlet[BinaryDialectInbound] = Outlet("ServiceBoundOut")
    val in2: Inlet[BinaryDialectOutbound] = Inlet("ClientBoundIn")
    val out2: Outlet[BinaryDialectOutbound] = Outlet("ClientBoundOut")
    override val shape: BidiShape[BinaryDialectInbound, BinaryDialectInbound, BinaryDialectOutbound, BinaryDialectOutbound] = BidiShape(in1, out1, in2, out2)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {

      case object Timer

      import PingInjectorStage._

      implicit val publisher = EvtContext(classOf[PingInjectorStage], nodeCfg.config, 'token -> sessionId)


      private val clientBound = mutable.Queue[BinaryDialectOutbound]()
      private val serviceBound = mutable.Queue[BinaryDialectInbound]()

      override def preStart(): Unit = {
        pull(in1)
        pull(in2)
        doPing()
        schedulePeriodicallyWithInitialDelay(Timer, interval, interval)
      }


      setHandler(in1, new InHandler {
        override def onPush(): Unit = {
          grab(in1) match {
            case BinaryDialectPong(ts) =>
              val now = System.currentTimeMillis() % Int.MaxValue
              val diff = now - ts
              publisher.raise(Evt.ServerClientPing, 'ms -> diff)
            case x: BinaryDialectInbound => pushToServer(x)
          }
          if (canPullFromClientNow) pull(in1)
        }
      })
      setHandler(out1, new OutHandler {
        override def onPull(): Unit = if (serviceBound.nonEmpty) {
          push(out1, serviceBound.dequeue())
          if (canPullFromClientNow) pull(in1)
        }
      })
      setHandler(in2, new InHandler {
        override def onPush(): Unit = {
          grab(in2) match {
            case x: BinaryDialectOutbound => pushToClient(x)
            case _ =>
          }
          if (canPullFromServiceNow) pull(in2)
        }
      })
      setHandler(out2, new OutHandler {
        override def onPull(): Unit = if (clientBound.nonEmpty) {
          push(out2, clientBound.dequeue())
          if (canPullFromServiceNow) pull(in2)
        }
      })


      override protected def onTimer(timerKey: Any): Unit = doPing()

      def doPing() = pushToClient(BinaryDialectPing((System.currentTimeMillis() % Int.MaxValue).toInt))

      def pushToClient(x: BinaryDialectOutbound) = {
        if (isAvailable(out2) && clientBound.isEmpty) push(out2, x)
        else {
          val shouldEnqueue = x match {
            case _: BinaryDialectPing => !clientBound.exists {
              case _: BinaryDialectPing => true
              case _ => false
            }
            case _ => true
          }
          if (shouldEnqueue) clientBound.enqueue(x)
        }
        if (canPullFromServiceNow) pull(in2)
      }

      def pushToServer(x: BinaryDialectInbound) = {
        if (isAvailable(out1) && serviceBound.isEmpty) push(out1, x)
        else serviceBound.enqueue(x)
        if (canPullFromClientNow) pull(in1)
      }

      def canPullFromServiceNow = !hasBeenPulled(in2) && clientBound.size < outBuffer

      def canPullFromClientNow = !hasBeenPulled(in1) && serviceBound.size < inBuffer

    }
  }


  override def buildStage(sessionId: String, componentId: String)(implicit serviceCfg: ServiceConfig, nodeCfg: RootConfig) =
    if (serviceCfg.asBoolean("ping.enabled", defaultValue = true)) Some(BidiFlow.fromGraph(new PingInjector(serviceCfg, nodeCfg, sessionId))) else None

}
