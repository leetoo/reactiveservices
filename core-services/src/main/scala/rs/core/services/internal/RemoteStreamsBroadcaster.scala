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
package rs.core.services.internal

import java.util

import akka.actor.ActorRef
import com.typesafe.config._
import rs.core.actors.BaseActor
import rs.core.services.StreamId
import rs.core.services.internal.InternalMessages.StreamUpdate
import rs.core.stream.{StreamState, StreamStateTransition}
import rs.core.sysevents.{CommonEvt, EvtPublisherContext}

import scala.collection.mutable


trait RemoteStreamsBroadcasterEvt extends CommonEvt {
  val StreamStateTransition = "StreamStateTransition".trace
  val InitiatingStreamForDestination = "InitiatingStreamForDestination".trace
  val ClosingStreamForDestination = "ClosingStreamForDestination".trace
  val StreamUpdateSent = "StreamUpdateSent".trace
}

trait RemoteStreamsBroadcaster extends BaseActor with RemoteStreamsBroadcasterEvt {

  private val targets: mutable.Map[ActorRef, ConsumerWithStreamSinks] = mutable.HashMap()
  private val streams: mutable.Map[StreamId, StreamBroadcaster] = mutable.HashMap()

  final def stateOf(key: StreamId): Option[StreamState] = streams get key flatMap (_.state)

  final def newConsumerDemand(consumer: ActorRef, demand: Long): Unit = targets get consumer foreach (_.addDemand(demand))

  final def stateTransitionFor(key: StreamId, transition: => StreamStateTransition): Boolean = StreamStateTransition { ctx =>
    ctx + ('stream -> key)
    streams get key match {
      case None =>
        ctx + ('active -> false)
        true
      case Some(s) =>
        val result = s.run(transition)
        ctx +('active -> true, 'successful -> result, 'tx -> transition)
        result
    }
  }

  final def initiateTarget(ref: ActorRef) = if (!targets.contains(ref)) newTarget(ref)

  final def initiateStreamFor(ref: ActorRef, key: StreamId) = InitiatingStreamForDestination { ctx =>
    ctx +('stream -> key, 'ref -> ref)
    val target = targets getOrElse(ref, newTarget(ref))
    val stream = streams getOrElse(key, {
      ctx + ('broadcaster -> "new")
      newStreamBroadcaster(key)
    })

    val sink = target locateExistingSinkFor key match {
      case None =>
        ctx + ('consumer -> "new")
        val newSink = target addStream key
        stream addSink newSink
        newSink
      case Some(s) =>
        ctx + ('consumer -> "existing")
        s
    }
    sink.resetDownstreamView()
  }

  private def newStreamBroadcaster(key: StreamId) = {
    val sb = new StreamBroadcaster()
    streams += key -> sb
    sb
  }

  private def newTarget(ref: ActorRef) = {
    val target = new ConsumerWithStreamSinks(ref, self, componentId)
    targets += ref -> target
    target
  }

  final def closeStreamFor(ref: ActorRef, key: StreamId) = ClosingStreamForDestination { ctx =>
    ctx +('stream -> key, 'ref -> ref)
    targets get ref foreach { target =>
      target locateExistingSinkFor key foreach { existingSink =>
        target.closeStream(key)
        streams get key foreach { stream =>
          stream removeSink existingSink
        }
      }
    }
  }

  private class ConsumerWithStreamSinks(val ref: ActorRef, self: ActorRef, parentComponentId: String)(implicit val config: Config) extends ConsumerDemandTracker with EvtPublisherContext {
    private val streamKeyToSink: mutable.Map[StreamId, StreamSink] = mutable.HashMap()
    private val streams: util.ArrayList[StreamSink] = new util.ArrayList[StreamSink]()
    private val canUpdate = () => hasDemand
    private var nextPublishIdx = 0

    def locateExistingSinkFor(key: StreamId): Option[StreamSink] = streamKeyToSink get key

    def addStream(key: StreamId): StreamSink = {
      closeStream(key)
      val newSink = new StreamSink(canUpdate, updateForTarget(key))
      streamKeyToSink += key -> newSink
      streams add newSink
      newSink
    }

    def closeStream(key: StreamId) = {
      streamKeyToSink get key foreach { existingSink =>
        streamKeyToSink -= key
        streams remove existingSink
      }
    }

    private def updateForTarget(key: StreamId)(tran: StreamStateTransition) = fulfillDownstreamDemandWith {
      ref.tell(StreamUpdate(key, tran), self)
      StreamUpdateSent('stream -> key, 'target -> ref, 'payload -> tran)
    }

    def addDemand(demand: Long): Unit = {
      addConsumerDemand(demand)
      publishToAll()
    }

    private def publishToAll() = if (streams.size() > 0) {
      val cycles = streams.size()
      var cnt = 0
      while (cnt < cycles && hasDemand) {
        streams get nextPublishIdx publishPending()
        nextPublishIdx += 1
        if (nextPublishIdx == streams.size()) nextPublishIdx = 0
        cnt += 1
      }
    }

    override def componentId: String = parentComponentId
  }

  private class StreamBroadcaster {
    private val sinks: util.ArrayList[StreamSink] = new util.ArrayList[StreamSink]()
    private var latestState: Option[StreamState] = None

    def state = latestState

    def removeSink(existingSink: StreamSink) = sinks remove existingSink

    def run(transition: StreamStateTransition): Boolean =
      transitionLocalStateWith(transition) match {
        case None => false
        case Some(newState) =>
          var idx = 0
          while (idx < sinks.size()) {
            sinks get idx onTransition(transition, newState)
            idx += 1
          }
          true
      }

    private def transitionLocalStateWith(transition: StreamStateTransition) = {
      if (transition applicableTo latestState)
        latestState = transition toNewStateFrom latestState
      else
        latestState = None
      latestState
    }

    def addSink(streamSink: StreamSink) = {
      sinks add streamSink
      streamSink publish latestState
    }

  }

  private class StreamSink(canUpdate: () => Boolean, update: StreamStateTransition => Unit) {
    private var pendingState: Option[StreamState] = None
    private var remoteView: Option[StreamState] = None

    def resetDownstreamView() = remoteView = None

    def publish(state: Option[StreamState]) = {
      pendingState = state
      publishPending()
    }

    def publishPending() =
      if (canUpdate()) {
        pendingState foreach updateFrom
        pendingState = None
      }

    private def updateFrom(newState: StreamState) = newState transitionFrom remoteView foreach update

    def onTransition(transition: StreamStateTransition, newState: StreamState) =
      if (canUpdate()) {
        if (transition applicableTo remoteView) update(transition) else updateFrom(newState)
        remoteView = Some(newState)
        pendingState = None
      } else {
        pendingState = Some(newState)
      }

  }


}









