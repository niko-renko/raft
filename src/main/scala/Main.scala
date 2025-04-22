import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.TimerScheduler

object Process {
  final private case class State(
      timers: TimerScheduler[Message],
      refs: List[ActorRef[Message]]
  )

  sealed trait Message
  final case class Refs(refs: List[ActorRef[Message]]) extends Message
  private case object Timeout extends Message

  private case object Timer

  def apply(): Behavior[Message] =
    Behaviors.receive { (context, message) =>
      message match {
        case Refs(refs) => {
          context.log.info("Received refs: {}", refs)
          Behaviors.withTimers(timers => {
            val state = State(timers, refs)
            this.startTimeout(state)
            this.main(state)
          })
        }
        case _ => Behaviors.stopped
      }
    }

  private def main(state: State): Behavior[Message] =
    Behaviors.receive { (context, message) =>
      {
        context.log.info("Timeout")
        this.startTimeout(state)
        this.main(state)
      }
    }

  private def startTimeout(state: State) = {
    state.timers.startSingleTimer(
      Timer,
      Timeout,
      FiniteDuration(1000, TimeUnit.MILLISECONDS)
    )
  }
}

object Guardian {
  final case class Start(processes: Int)

  def apply(): Behavior[Start] = Behaviors.receive { (context, message) =>
    context.log.info("Starting {} processes", message.processes)
    val refs = (0 until message.processes)
      .map(i => context.spawn(Process(), s"process-$i"))
      .toList
    refs.foreach(ref => ref ! Process.Refs(refs))
    Behaviors.ignore
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    if (args.size != 1) {
      println("Usage: Main processes")
      sys.exit(1)
    }

    val processes = args(0).toInt
    if (processes <= 0) {
      println("Process number must be greater than 0")
      sys.exit(1)
    }

    val system = ActorSystem(Guardian(), "guardian")
    system ! Guardian.Start(processes)
  }
}
