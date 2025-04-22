import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.TimerScheduler

final class ProcessID(val id: Int)

final class Processes(val refs: Map[ProcessID, ActorRef[Process.Message]])
    extends Iterable[(ProcessID, ActorRef[Process.Message])] {
  def get_ref(id: ProcessID): ActorRef[Process.Message] = this.refs(id)

  def peers(of: ProcessID): Iterator[(ProcessID, ActorRef[Process.Message])] =
    this.refs.filter(_._1 != of).iterator

  override def iterator: Iterator[(ProcessID, ActorRef[Process.Message])] =
    this.refs.iterator
}

object Process {
  sealed trait Message
  final case class Refs(self: ProcessID, refs: Processes) extends Message
}

final class Process[T] {
  final private case class State(
      self: ProcessID,
      refs: Processes,
      timers: TimerScheduler[Process.Message],
      currentTerm: Int,
      votedFor: Option[ProcessID],
      log: List[T],
      commitIndex: Int,
      lastApplied: Int,
      nextIndex: Map[ProcessID, Int],
      matchIndex: Map[ProcessID, Int]
  )

  private case object Timeout extends Process.Message
  private case object Timer

  def apply(): Behavior[Process.Message] =
    Behaviors.receive { (context, message) =>
      message match {
        case Process.Refs(self, refs) => {
          context.log.info("Received refs: {}", refs)
          Behaviors.withTimers(timers => {
            val state =
              State(self, refs, timers, 0, None, List(), 0, 0, Map(), Map())
            this.startTimeout(state)
            this.main(state)
          })
        }
        case _ => Behaviors.stopped
      }
    }

  private def main(state: State): Behavior[Process.Message] =
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
    val refs_map = (0 until message.processes)
      .map(i =>
        (ProcessID(i), context.spawn(Process[String]()(), s"process-$i"))
      )
      .toMap
    val refs = Processes(refs_map)
    refs.foreach((id, ref) => ref ! Process.Refs(id, refs))
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
