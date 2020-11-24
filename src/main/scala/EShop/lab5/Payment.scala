package EShop.lab5

import EShop.lab3.Payment.{DoPayment, PaymentConfirmed}
import EShop.lab5.Payment.{PaymentRejected, PaymentRestarted}
import EShop.lab5.PaymentService.{PaymentClientError, PaymentServerError, PaymentSucceeded}
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props}

import scala.concurrent.duration._

object Payment {

  case object PaymentRejected
  case object PaymentRestarted

  def props(method: String, orderManager: ActorRef, checkout: ActorRef) =
    Props(new Payment(method, orderManager, checkout))

}

class Payment(
  method: String,
  orderManager: ActorRef,
  checkout: ActorRef
) extends Actor
  with ActorLogging {

  override def receive: Receive = {
    case DoPayment => context.actorOf(PaymentService.props(method, self))
    case PaymentSucceeded =>
      orderManager ! PaymentConfirmed
      checkout ! PaymentConfirmed
  }

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.seconds) {
      case _: PaymentServerError =>
        notifyAboutRestart()
        Restart
      case _: PaymentClientError =>
        notifyAboutRejection()
        Stop
    }

  //please use this one to notify when supervised actor was stopped
  private def notifyAboutRejection(): Unit = {
    orderManager ! PaymentRejected
    checkout ! PaymentRejected
  }

  //please use this one to notify when supervised actor was restarted
  private def notifyAboutRestart(): Unit = {
    orderManager ! PaymentRestarted
    checkout ! PaymentRestarted
  }
}
