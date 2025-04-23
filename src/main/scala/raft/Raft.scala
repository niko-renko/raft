package raft

import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import java.util.concurrent.TimeUnit

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.TimerScheduler

final private case class State[T <: Serializable](
    self: ProcessID,
    refs: Processes[Message[T]],
    timers: TimerScheduler[Message[T]],
    commitIndex: Int,
    lastApplied: Int,
    nextIndex: Map[ProcessID, Int],
    matchIndex: Map[ProcessID, Int]
)

private sealed trait Message[T <: Serializable]

// Public
final case class Refs[T <: Serializable](
    self: ProcessID,
    refs: Processes[Message[T]]
) extends Message[T]
final case class Append[T <: Serializable](
    entries: List[T]
) extends Message[T]

// Private
final private case class Timeout[T <: Serializable](
) extends Message[T]
final private case class AppendEntries[T <: Serializable](
    term: Int,
    leaderId: ProcessID,
    prevLogIndex: Int,
    prevLogTerm: Int,
    entries: List[T],
    leaderCommit: Int
) extends Message[T]
final private case class AppendEntriesResponse[T <: Serializable](
    term: Int,
    success: Boolean
) extends Message[T]
final private case class RequestVote[T <: Serializable](
    term: Int,
    candidateId: ProcessID,
    lastLogIndex: Int,
    lastLogTerm: Int
) extends Message[T]
final private case class RequestVoteResponse[T <: Serializable](
    term: Int,
    voteGranted: Boolean
) extends Message[T]

private case object Election

final class Process[T <: Serializable] {
  def apply(): Behavior[Message[T]] =
    Behaviors.receive { (context, message) =>
      message match {
        case Refs(_, refs) if refs.size % 2 == 0 =>
          Behaviors.stopped
        case Refs(self, refs) =>
          Behaviors.withTimers(timers => {
            context.log.info("Starting process {}", self.id)
            val state =
              State(self, refs, timers, 0, 0, Map(), Map())
            val persistent = PersistentState.load[T](self.id)
            this.resetTimer(state)
            this.main(state, persistent)
          })
        case _ => Behaviors.stopped
      }
    }

  private def main(
      state: State[T],
      persistent: PersistentState[T]
  ): Behavior[Message[T]] =
    Behaviors.receive { (context, message) =>
      {
        message match {
          case Append(entries) => {
            context.log.info("Received Append from {}", entries)
            this.main(state, persistent)
          }

          case Timeout() => {
            context.log.info(
              "Timeout -- converting to candidate {}",
              state.self
            )

            val npersistent = persistent.copy(
              term = persistent.term + 1,
              votedFor = Some(state.self)
            )
            npersistent.save(state.self.id)

            val lastLogIndex = npersistent.log.length - 1
            val lastLogTerm = if (lastLogIndex < 0) {
              0
            } else {
              val (lastLogTerm, _) = npersistent.log(lastLogIndex)
              lastLogTerm
            }

            state.refs
              .peers(state.self)
              .foreach((id, ref) =>
                ref ! RequestVote(
                  npersistent.term,
                  state.self,
                  lastLogIndex,
                  lastLogTerm
                )
              )
            this.resetTimer(state)
            this.main(state, npersistent)
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
            this.resetTimer(state)
            this.main(state, persistent)
          }
          case AppendEntriesResponse(term, success) => {
            context.log.info("Received AppendEntriesResponse from {}", term)
            this.main(state, persistent)
          }
          case RequestVote(term, candidateId, lastLogIndex, lastLogTerm) => {
            context.log.info("Received RequestVote from {}", candidateId)
            val voteGranted = true
            if (voteGranted) {
              context.log.info("Granted vote to {}", candidateId)
              state.refs.getRef(candidateId) ! RequestVoteResponse(
                persistent.term,
                true
              )
              this.resetTimer(state)
            } else {
              context.log.info("Denied vote to {}", candidateId)
              state.refs.getRef(candidateId) ! RequestVoteResponse(
                persistent.term,
                false
              )
            }
            this.main(state, persistent)
          }
          case RequestVoteResponse(term, voteGranted) => {
            context.log.info("Received RequestVoteResponse from {}", term)
            this.main(state, persistent)
          }
          case _ => Behaviors.stopped
        }
      }
    }

  private def resetTimer(state: State[T]) = {
    val timeout = 150 + Random.nextInt(151)
    state.timers.cancel(Election)
    state.timers.startSingleTimer(
      Election,
      Timeout(),
      FiniteDuration(timeout, TimeUnit.MILLISECONDS)
    )
  }
}
