package EShop.lab4

import EShop.lab2.CartActor
import EShop.lab3.Payment
import akka.actor.{ActorRef, Cancellable, Props}
import akka.event.{Logging, LoggingReceive}
import akka.persistence.PersistentActor

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object PersistentCheckout {

  def props(cartActor: ActorRef, persistenceId: String) =
    Props(new PersistentCheckout(cartActor, persistenceId))
}

class PersistentCheckout(
                          cartActor: ActorRef,
                          val persistenceId: String
                        ) extends PersistentActor {

  import EShop.lab2.Checkout._
  private val scheduler             = context.system.scheduler
  private val log                   = Logging(context.system, this)
  val timerDuration: FiniteDuration = 1.seconds

  private def checkoutTimer: Cancellable =
    context.system.scheduler.scheduleOnce(timerDuration, self, ExpireCheckout)

  private def paymentTimer: Cancellable =
    context.system.scheduler.scheduleOnce(timerDuration, self, ExpirePayment)

  private def updateState(event: Event, timer: Option[Cancellable] = None): Unit = {
    context.become(event match {
      case CheckoutStarted                => selectingDelivery(timer.getOrElse(checkoutTimer))
      case DeliveryMethodSelected(method) => selectingPaymentMethod(timer.getOrElse(checkoutTimer))
      case CheckOutClosed                 => closed
      case CheckoutCancelled              => cancelled
      case PaymentStarted(payment)        => processingPayment(timer.getOrElse(paymentTimer))
    })
  }

  def receiveCommand: Receive = {
    case StartCheckout =>
      persist(CheckoutStarted) { event =>
        updateState(event)
      }
  }

  def selectingDelivery(timer: Cancellable): Receive = {
    case SelectDeliveryMethod(method: String) =>
      log.info(s"Selected $method delivery method")
      timer.cancel()
      persist(DeliveryMethodSelected(method)) { event =>
        updateState(event)
      }
    case ExpireCheckout =>
      log.info(s"Checkout has been cancelled")
      timer.cancel()
      persist(CheckoutCancelled) { event =>
        updateState(event)
      }
    case CancelCheckout =>
      log.info(s"Checkout time has expired")
      timer.cancel()
      persist(CheckoutCancelled) { event =>
        updateState(event)
      }
  }

  def selectingPaymentMethod(timer: Cancellable): Receive = {
    case SelectPayment(method: String) =>
      log.info(s"Selected $method payment method")
      timer.cancel()
      val payment = context.actorOf(Payment.props(method, sender, self), "paymentActor")
      persist(PaymentStarted(payment)) { event =>
        updateState(event)
      }
    case ExpireCheckout =>
      timer.cancel()
      persist(CheckoutCancelled) { event =>
        updateState(event)
      }
    case CancelCheckout =>
      timer.cancel()
      persist(CheckoutCancelled) { event =>
        updateState(event)
      }
  }

  def processingPayment(timer: Cancellable): Receive = {
    case ConfirmPaymentReceived =>
      timer.cancel()
      cartActor ! CartActor.ConfirmCheckoutClosed
      persist(CheckOutClosed) { event =>
        updateState(event)
      }
    case ExpirePayment  =>
      timer.cancel()
      persist(CheckoutCancelled) { event =>
        updateState(event)
      }
    case CancelCheckout =>
      timer.cancel()
      persist(CheckoutCancelled) { event =>
        updateState(event)
      }
    case ExpireCheckout =>
      timer.cancel()
      persist(CheckoutCancelled) { event =>
        updateState(event)
      }
  }

  def cancelled: Receive = {
    case _ =>
      log.info(s"Checkout is cancelled")
      context.stop(self)
  }

  def closed: Receive = {
    case _ => context.stop(self)
  }

  override def receiveRecover: Receive = {
    case event: Event                       => updateState(event)
    case (event: Event, timer: Cancellable) => updateState(event, Option(timer))
  }
}
