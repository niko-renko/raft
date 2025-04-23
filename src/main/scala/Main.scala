import java.util.concurrent.TimeUnit
import java.io.{
  ObjectOutputStream,
  FileOutputStream,
  ObjectInputStream,
  FileInputStream
}
import java.nio.file.{Files, Paths}
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.TimerScheduler

final class ProcessID(val id: Int)

final class Processes(val refs: Map[ProcessID, ActorRef[Process.Message]])
    extends Iterable[(ProcessID, ActorRef[Process.Message])] {
  def getRef(id: ProcessID): ActorRef[Process.Message] = this.refs(id)

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
  final private case class PersistentState(
      currentTerm: Int,
      votedFor: Option[ProcessID],
      log: List[T]
  ) extends Serializable

  final private case class State(
      self: ProcessID,
      refs: Processes,
      timers: TimerScheduler[Process.Message],
      persistentState: PersistentState,
      commitIndex: Int,
      lastApplied: Int,
      nextIndex: Map[ProcessID, Int],
      matchIndex: Map[ProcessID, Int]
  )

  private case object Timeout extends Process.Message
  private case class AppendEntries(
      term: Int,
      leaderId: ProcessID,
      prevLogIndex: Int,
      prevLogTerm: Int,
      entries: List[T],
      leaderCommit: Int
  ) extends Process.Message
  private case class AppendEntriesResponse(term: Int, success: Boolean)
      extends Process.Message
  private case class RequestVote(
      term: Int,
      candidateId: ProcessID,
      lastLogIndex: Int,
      lastLogTerm: Int
  ) extends Process.Message
  private case class RequestVoteResponse(term: Int, voteGranted: Boolean)
      extends Process.Message

  private case object Election

  def apply(): Behavior[Process.Message] =
    Behaviors.receive { (context, message) =>
      message match {
        case Process.Refs(_, refs) if refs.size % 2 == 0 =>
          Behaviors.stopped
        case Process.Refs(self, refs) =>
          Behaviors.withTimers(timers => {
            context.log.info("Starting process {}", self.id)
            val state =
              State(self, refs, timers, load(self.id), 0, 0, Map(), Map())
            this.startTimer(state)
            this.main(state)
          })
        case _ => Behaviors.stopped
      }
    }

  private def main(state: State): Behavior[Process.Message] =
    Behaviors.receive { (context, message) =>
      {
        message match {
          case Timeout => {
            context.log.info("Timeout")
            this.main(state)
          }
          case AppendEntries(
                term,
                leaderId,
                prevLogIndex,
                prevLogTerm,
                entries,
                leaderCommit
              ) => {
            context.log.info("Received AppendEntries from {}", leaderId)
            this.main(state)
          }
          case AppendEntriesResponse(term, success) => {
            context.log.info("Received AppendEntriesResponse from {}", term)
            this.main(state)
          }
          case RequestVote(term, candidateId, lastLogIndex, lastLogTerm) => {
            context.log.info("Received RequestVote from {}", candidateId)
            this.main(state)
          }
          case RequestVoteResponse(term, voteGranted) => {
            context.log.info("Received RequestVoteResponse from {}", term)
            this.main(state)
          }
          case _ => Behaviors.stopped
        }
      }
    }

  private def startTimer(state: State) = {
    val timeout = 150 + Random.nextInt(151)
    state.timers.startSingleTimer(
      Election,
      Timeout,
      FiniteDuration(timeout, TimeUnit.MILLISECONDS)
    )
  }

  private def save(state: State): Unit = {
    val filename = s"persistent-state/${state.self.id}.state"
    val stream = new ObjectOutputStream(new FileOutputStream(filename))
    stream.writeObject(state.persistentState)
    stream.close()
    ()
  }

  private def load(id: Int): PersistentState = {
    val filename = s"persistent-state/${id}.state"
    if (!Files.exists(Paths.get(filename))) {
      return PersistentState(0, None, List())
    }
    val stream = new ObjectInputStream(new FileInputStream(filename))
    val persistentState = stream.readObject().asInstanceOf[PersistentState]
    stream.close()
    persistentState
  }
}

object Guardian {
  final case class Start(processes: Int)

  def apply(): Behavior[Start] = Behaviors.receive { (context, message) =>
    context.log.info("Starting {} processes", message.processes)
    val refsMap = (0 until message.processes)
      .map(i =>
        (ProcessID(i), context.spawn(Process[String]()(), s"process-$i"))
      )
      .toMap
    val refs = Processes(refsMap)
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
