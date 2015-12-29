/*
 * Copyright 2014-15 Intelix Pty Ltd
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

package rs.core.actors

import akka.actor.{Actor, ActorRef, FSM, Terminated}
import rs.core.config.{NodeConfig, WithActorSystemConfig}
import rs.core.sysevents.EvtPublisherContext

trait ActorState

trait StatefulActor[T] extends FSM[ActorState, T] with BaseActor {

  private var chainedUnhandled: StateFunction = {
    case Event(Terminated(ref), _) => terminatedFuncChain.foreach(_ (ref)); stay()
  }

  final override def onMessage(f: Receive) = otherwise {
    case Event(x, _) if f.isDefinedAt(x) => f(x); stay()
  }

  final def otherwise(f: StateFunction) = {
    chainedUnhandled = f orElse chainedUnhandled
  }

  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    initialize()
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    initialize()
  }

  def transitionTo(state: ActorState) = {
    if (stateName != state) StateChange('to -> state, 'from -> stateName)
    goto(state)
  }

  whenUnhandled {
    case x if chainedUnhandled.isDefinedAt(x) => chainedUnhandled(x)
  }

}

trait JBaseActor extends BaseActor {

  private var chainedFunc: Receive = {
    case Terminated(ref) => terminatedFuncChain.foreach(_ (ref))
  }

  override final val receive: Actor.Receive = {
    case x if chainedFunc.isDefinedAt(x) => chainedFunc(x)
    case x => unhandled(x)
  }

  override final def onMessage(f: Receive): Unit = chainedFunc = f orElse chainedFunc
}


trait BaseActor extends WithActorSystemConfig with ActorUtils with CommonActorEvt with EvtPublisherContext {

  private val pathAsString = self.path.toStringWithoutAddress
  protected[actors] var terminatedFuncChain: Seq[ActorRef => Unit] = Seq.empty

  override implicit lazy val nodeCfg: NodeConfig = NodeConfig(config)

  def onActorTerminated(f: ActorRef => Unit) = terminatedFuncChain = terminatedFuncChain :+ f

  addEvtFields('path -> pathAsString, 'nodeid -> nodeId)

  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    PreRestart('reason -> reason.getMessage, 'msg -> message, 'path -> pathAsString)
    super.preRestart(reason, message)
  }

  @throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    PostRestart('reason -> reason.getMessage, 'path -> pathAsString)
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    PreStart('path -> pathAsString)
    super.preStart()
  }

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    super.postStop()
    PostStop('path -> pathAsString)
  }


  def onMessage(f: Receive)


}