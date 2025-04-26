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
    matchIndex: Map[ProcessID, Int],
    votes: Int
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
final private case class ElectionTimeout[T <: Serializable](
) extends Message[T]
final private case class HeartbeatTimeout[T <: Serializable](
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
private case object Heartbeat

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
              State(self, refs, timers, 0, 0, Map(), Map(), 0)
            val persistent = PersistentState.load[T](self.id)
            this.resetElection(state)
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

          case ElectionTimeout() => {
            context.log.info(
              "ElectionTimeout -- converting to candidate {}",
              state.self
            )

            val npersistent = persistent.copy(
              term = persistent.term + 1,
              votedFor = Some(state.self)
            )
            val nstate = state.copy(
              votes = 1
            )
            npersistent.save(nstate.self.id)

            val (lastLogIndex, lastLogTerm) = this.lastEntry(npersistent)
            nstate.refs
              .peers(nstate.self)
              .foreach((id, ref) =>
                ref ! RequestVote(
                  npersistent.term,
                  nstate.self,
                  lastLogIndex,
                  lastLogTerm
                )
              )
            this.resetElection(nstate)
            this.main(nstate, npersistent)
          }
          case HeartbeatTimeout() => {
            context.log.info("HeartbeatTimeout")
            this.resetHeartbeat(state)
            state.refs
              .peers(state.self)
              .foreach((id, ref) =>
                ref ! AppendEntries(
                  persistent.term,
                  state.self,
                  state.commitIndex,
                  state.lastApplied,
                  List(),
                  state.commitIndex
                )
              )
            this.main(state, persistent)
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
            this.resetElection(state)
            this.main(state, persistent)
          }
          case AppendEntriesResponse(term, success) => {
            context.log.info("Received AppendEntriesResponse from {}", term)
            this.main(state, persistent)
          }
          case RequestVote(term, candidateId, lastLogIndex, lastLogTerm) => {
            context.log.info("Received RequestVote from {}", candidateId)
            val (thisLastLogIndex, thisLastLogTerm) = this.lastEntry(persistent)
            val voteGranted =
              term > persistent.term && (lastLogTerm > thisLastLogTerm || (lastLogTerm == thisLastLogTerm && lastLogIndex >= thisLastLogIndex))

            if (voteGranted) {
              context.log.info("Granted vote to {}", candidateId)
              val npersistent =
                persistent.copy(term = term, votedFor = Some(candidateId))
              npersistent.save(state.self.id)
              state.refs.getRef(candidateId) ! RequestVoteResponse(
                npersistent.term,
                true
              )
              this.resetElection(state)
              this.main(state, npersistent)
            } else {
              context.log.info("Denied vote to {}", candidateId)
              state.refs.getRef(candidateId) ! RequestVoteResponse(
                persistent.term,
                false
              )
              this.main(state, persistent)
            }
          }
          case RequestVoteResponse(term, voteGranted) => {
            context.log.info("Received RequestVoteResponse from {}", term)
            if (term < persistent.term) {
              this.main(state, persistent)
            } else if (term > persistent.term) {
              val npersistent = persistent.copy(term = term)
              npersistent.save(state.self.id)
              this.main(state, npersistent)
            } else if (state.votes > state.refs.size / 2) {
              this.main(state, persistent)
            } else {
              val nstate = state.copy(
                votes = state.votes + 1
              )

              if (nstate.votes > nstate.refs.size / 2) {
                context.log.info(
                  "{} has received majority of votes, becoming leader",
                  nstate.self
                )
                state.timers.cancel(Election)
                this.resetHeartbeat(nstate)
              }

              this.main(nstate, persistent)
            }
          }
          case _ => Behaviors.stopped
        }
      }
    }

  private def lastEntry(persistent: PersistentState[T]): (Int, Int) = {
    val lastLogIndex = persistent.log.length - 1
    val lastLogTerm = if (lastLogIndex < 0) {
      -1
    } else {
      val (lastLogTerm, _) = persistent.log(lastLogIndex)
      lastLogTerm
    }
    (lastLogIndex, lastLogTerm)
  }

  private def resetElection(state: State[T]) = {
    val timeout = 150 + Random.nextInt(151)
    state.timers.cancel(Election)
    state.timers.startSingleTimer(
      Election,
      ElectionTimeout(),
      FiniteDuration(timeout, TimeUnit.MILLISECONDS)
    )
  }

  private def resetHeartbeat(state: State[T]) = {
    state.timers.cancel(Heartbeat)
    state.timers.startSingleTimer(
      Heartbeat,
      HeartbeatTimeout(),
      FiniteDuration(25, TimeUnit.MILLISECONDS)
    )
  }
}
