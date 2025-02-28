package protocols

import akka.actor.typed._
import akka.actor.typed.scaladsl._

import scala.concurrent.duration._

object Transactor {

  sealed trait PrivateCommand[T] extends Product with Serializable
  final case class Committed[T](session: ActorRef[Session[T]], value: T) extends PrivateCommand[T]
  final case class RolledBack[T](session: ActorRef[Session[T]]) extends PrivateCommand[T]

  sealed trait Command[T] extends PrivateCommand[T]
  final case class Begin[T](replyTo: ActorRef[ActorRef[Session[T]]]) extends Command[T]

  sealed trait Session[T] extends Product with Serializable
  final case class Extract[T, U](f: T => U, replyTo: ActorRef[U]) extends Session[T]
  final case class Modify[T, U](f: T => T, id: Long, reply: U, replyTo: ActorRef[U]) extends Session[T]
  final case class Commit[T, U](reply: U, replyTo: ActorRef[U]) extends Session[T]
  final case class Rollback[T]() extends Session[T]

  /**
    * @return A behavior that accepts public [[Command]] messages. The behavior
    *         should be wrapped in a [[SelectiveReceive]] decorator (with a capacity
    *         of 30 messages) so that beginning new sessions while there is already
    *         a currently running session is deferred to the point where the current
    *         session is terminated.
    * @param value Initial value of the transactor
    * @param sessionTimeout Delay before rolling back the pending modifications and
    *                       terminating the session
    */
  def apply[T](value: T, sessionTimeout: FiniteDuration): Behavior[Command[T]] =
    SelectiveReceive(30, idle(value, sessionTimeout).narrow)

  /**
    * @return A behavior that defines how to react to any [[PrivateCommand]] when the transactor
    *         has no currently running session.
    *         [[Committed]] and [[RolledBack]] messages should be ignored, and a [[Begin]] message
    *         should create a new session.
    *
    * @param value Value of the transactor
    * @param sessionTimeout Delay before rolling back the pending modifications and
    *                       terminating the session
    *
    * Note: To implement the timeout you have to use `ctx.scheduleOnce` instead of `Behaviors.withTimers`, due
    *       to a current limitation of Akka: https://github.com/akka/akka/issues/24686
    */
  private def idle[T](value: T, sessionTimeout: FiniteDuration): Behavior[PrivateCommand[T]] =
    Behaviors receivePartial {

      case (ctx, Begin(replyTo)) =>
        val session = ctx.spawnAnonymous(sessionHandler(value, ctx.self, Set.empty))
        val next = inSession(value, sessionTimeout, session)
        ctx.watchWith(session, RolledBack(session))
        ctx.scheduleOnce(sessionTimeout, replyTo, RolledBack(session).session)
        replyTo ! session
        next

      case (_, Committed(_, _)) => Behaviors.ignore
      case (_, RolledBack(_)) => Behaviors.ignore
    }

  /**
    * @return A behavior that defines how to react to [[PrivateCommand]] messages when the transactor has
    *         a running session.
    *         [[Committed]] and [[RolledBack]] messages should commit and rollback the session, respectively.
    *         [[Begin]] messages should be unhandled (they will be handled by the [[SelectiveReceive]] decorator).
    *
    * @param rollbackValue Value to rollback to
    * @param sessionTimeout Timeout to use for the next session
    * @param sessionRef Reference to the child [[Session]] actor
    */
  private def inSession[T](rollbackValue: T, sessionTimeout: FiniteDuration, sessionRef: ActorRef[Session[T]]): Behavior[PrivateCommand[T]] =
    Behaviors receivePartial {

      case (_, Committed(session, value)) if session == sessionRef =>
        idle(value, sessionTimeout)

      case (ctx, RolledBack(session)) if session == sessionRef =>
        ctx stop session
        idle(rollbackValue, sessionTimeout)
    }

  /**
    * @return A behavior handling [[Session]] messages. See in the instructions
    *         the precise semantics that each message should have.
    *
    * @param currentValue The session’s current value
    * @param commit Parent actor reference, to send the [[Committed]] message to
    * @param done Set of already applied [[Modify]] messages
    */
  private def sessionHandler[T](currentValue: T, commit: ActorRef[Committed[T]], done: Set[Long]): Behavior[Session[T]] =
    Behaviors receive {

      case (ctx, Commit(reply, replyTo)) =>
        commit ! Committed(ctx.self, currentValue)
        replyTo ! reply
        Behaviors.stopped

      case (_, Rollback()) =>
        Behaviors.stopped

      case (_, Extract(f, replyTo)) =>
        replyTo ! f(currentValue)
        Behaviors.same

      case (_, Modify(f, id, reply, replyTo)) if done.isEmpty || id > done.max =>
        replyTo ! reply
        sessionHandler(f(currentValue), commit, done + id)

      case (_, Modify(_, id, reply, replyTo)) if id <= done.max =>
        replyTo ! reply
        Behaviors.same
    }
}