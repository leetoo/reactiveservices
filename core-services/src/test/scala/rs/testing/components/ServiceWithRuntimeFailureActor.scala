package rs.testing.components

import rs.core.services.{StatelessServiceActor, ServiceEvt}

trait ServiceWithRuntimeFailureEvents extends ServiceEvt {
  override def componentId: String = "ServiceWithRuntimeFailure"
}

object ServiceWithRuntimeFailureEvents extends ServiceWithRuntimeFailureEvents

object ServiceWithRuntimeFailureActor {
  var recoveryEnabled = false
  var failureCounter = 0
}

class ServiceWithRuntimeFailureActor(id: String) extends StatelessServiceActor(id) with ServiceWithRuntimeFailureEvents {
  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    self ! "tick"
  }

  onMessage {
    case "tick" =>
      if (ServiceWithInitialisationFailureActor.recoveryEnabled) ServiceWithInitialisationFailureActor.failureCounter += 1

      if (!ServiceWithInitialisationFailureActor.recoveryEnabled || ServiceWithInitialisationFailureActor.failureCounter < 5)
        throw new RuntimeException("simulated failure on first tick")

  }
}

