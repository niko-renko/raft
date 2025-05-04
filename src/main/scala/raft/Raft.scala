package raft

import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import java.util.concurrent.TimeUnit
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.TimerScheduler

import guardian.GetRefs

enum Role:
  case Follower
  case Candidate
  case Leader

final private case class State[T <: Serializable](
    self: ProcessID,
    refs: Processes[T],
    timers: TimerScheduler[Message[T]],
    commitIndex: Int,
    lastApplied: Int,
    nextIndex: Map[ProcessID, Int],
    matchIndex: Map[ProcessID, Int],
    votes: Int,
    role: Role
)

sealed trait Message[T <: Serializable]

// Public
final case class Refs[T <: Serializable](
    refs: Processes[T]
) extends Message[T]
final case class Append[T <: Serializable](
    entries: List[T]
) extends Message[T]
final case class Crash[T <: Serializable](
) extends Message[T]
final case class Pause[T <: Serializable](
    seconds: Int
) extends Message[T]

// Private
final private case class ElectionTimeout[T <: Serializable](
) extends Message[T]
final private case class HeartbeatTimeout[T <: Serializable](
) extends Message[T]
final private case class PauseTimeout[T <: Serializable](
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

private case object ElectionTimer
private case object HeartbeatTimer
private case object PauseTimer

final class Process[T <: Serializable] {
  def apply(self: ProcessID, parent: ActorRef[guardian.Message]): Behavior[Message[T]] =
    Behaviors.setup { context => 
      context.log.info("Starting process {}", self.id)
      parent ! GetRefs(self)
      Behaviors.receive { (context, message) => message match {
          case Refs(refs) if refs.size % 2 == 0 =>
            Behaviors.stopped
          case Refs(refs) =>
            Behaviors.withTimers(timers => {
              val state =
                State(self, refs, timers, 0, 0, Map(), Map(), 0, Role.Follower)
              val persistent = PersistentState.load[T](self.id)
              // Switch loop -- follower
              this.resetElection(state)
              this.stopHeartbeat(state)
              this.main(state, persistent)
            })
          case _ => Behaviors.stopped
        }
      }
    }

  private def main(
      state: State[T],
      persistent: PersistentState[T]
  ): Behavior[Message[T]] =
    Behaviors.receive { (context, message) =>
        message match {
          case Append(entries) => {
            context.log.info("Received Append for {}", entries)
            this.main(state, persistent)
          }
          case Crash() => {
            context.log.info("Crashing process {}", state.self)
            throw new Exception("Crashing process")
          }
          case Pause(seconds) => {
            context.log.info("Pausing process {} for {} seconds", state.self, seconds)
            state.timers.startSingleTimer(
              Pause,
              PauseTimeout(),
              FiniteDuration(seconds * 1000, TimeUnit.MILLISECONDS)
            )
            this.pause(state, persistent)
          }

          case ElectionTimeout() => {
            val npersistent = persistent.copy(
              term = persistent.term + 1,
              votedFor = Some(state.self)
            )
            val nstate = state.copy(
              role = Role.Candidate,
              votes = 1
            )
            npersistent.save(nstate.self.id)
            context.log.info("({}) Becoming candidate", npersistent.term)

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
            context.log.trace("HeartbeatTimeout")
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
            this.resetHeartbeat(state)
            this.main(state, persistent)
          }

          case AppendEntries(term, leaderId, _, _, _, _) if term < persistent.term => {
            context.log.trace("Received AppendEntries")
            state.refs.getRef(leaderId) ! AppendEntriesResponse(
              persistent.term,
              false
            )
            this.main(state, persistent)
          }
          case AppendEntries(term, leaderId, _, _, _, _) => {
            context.log.trace("Received AppendEntries")

            val npersistent = if (term > persistent.term) {
              val npersistent = persistent.copy(term = term)
              npersistent.save(state.self.id)
              npersistent
            } else {
              persistent
            }

            val nstate = state.role match {
              case Role.Leader => {
                assert(term > persistent.term)
                context.log.info("({}) Becoming follower", npersistent.term)
                val nstate = state.copy(role = Role.Follower)
                // Switch loop -- follower
                this.resetElection(nstate)
                this.stopHeartbeat(nstate)
                nstate
              }
              case Role.Candidate => {
                context.log.info("({}) Becoming follower", npersistent.term)
                val nstate = state.copy(role = Role.Follower)
                this.resetElection(nstate)
                nstate
              }
              case Role.Follower => {
                this.resetElection(state)
                state
              }
            }

            this.main(nstate, npersistent)
          }

          case AppendEntriesResponse(term, success) => {
            context.log.trace("Received AppendEntriesResponse")
            this.main(state, persistent)
          }

          case requestVote: RequestVote[T]
              if this.voteGranted(requestVote, persistent) => {
            val RequestVote(term, candidateId, _, _) = requestVote
            val npersistent =
              persistent.copy(term = term, votedFor = Some(candidateId))
            npersistent.save(state.self.id)
            context.log.info("({}) Granted vote to {}", npersistent.term, candidateId)
            state.refs.getRef(candidateId) ! RequestVoteResponse(
              npersistent.term,
              true
            )
            this.resetElection(state)
            this.main(state, npersistent)
          }
          case requestVote: RequestVote[T] => {
            val RequestVote(term, candidateId, _, _) = requestVote
            context.log.info("({}) Denied vote to {}", persistent.term, candidateId)
            state.refs.getRef(candidateId) ! RequestVoteResponse(
              persistent.term,
              false
            )
            this.main(state, persistent)
          }

          case RequestVoteResponse(term, _) if term > persistent.term => {
            val npersistent = persistent.copy(term = term)
            npersistent.save(state.self.id)
            this.main(state, npersistent)
          }
          case RequestVoteResponse(term, voteGranted)
              if term < persistent.term || state.role != Role.Candidate || !voteGranted =>
            this.main(state, persistent)
          case RequestVoteResponse(term, voteGranted) => {
            context.log.info("({}) Received a vote", persistent.term)
            val nstate = state.copy(
              votes = state.votes + 1,
              role = if ((state.votes + 1) > state.refs.size / 2) Role.Leader else Role.Candidate
            )

            if (nstate.role == Role.Leader) {
              context.log.info("({}) Becoming leader", persistent.term)
              // Switch loop -- leader
              this.stopElection(nstate)
              this.resetHeartbeat(nstate)
            }

            this.main(nstate, persistent)
          }

          case _ => Behaviors.stopped
        }
    }

  private def pause(state: State[T], persistent: PersistentState[T]): Behavior[Message[T]] = 
    Behaviors.receive { (context, message) => message match {
        case PauseTimeout() => {
          context.log.info("Resuming process {}", state.self)
          this.main(state, persistent)
        }
        case message => Behaviors.unhandled
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
  private def voteGranted(
      requestVote: RequestVote[T],
      persistent: PersistentState[T]
  ): Boolean = {
    val RequestVote(term, candidateId, lastLogIndex, lastLogTerm) = requestVote
    val (thisLastLogIndex, thisLastLogTerm) = this.lastEntry(persistent)
    if (term > persistent.term) {
      lastLogTerm > thisLastLogTerm || (lastLogTerm == thisLastLogTerm && lastLogIndex >= thisLastLogIndex)
    } else if (term == persistent.term) {
      persistent.votedFor.get == candidateId
    } else {
      false
    }
  }

  private def resetElection(state: State[T]) = {
    val timeout = 150 + Random.nextInt(151)
    state.timers.cancel(ElectionTimer)
    state.timers.startSingleTimer(
      ElectionTimer,
      ElectionTimeout(),
      FiniteDuration(timeout, TimeUnit.MILLISECONDS)
    )
  }
  private def stopElection(state: State[T]) = state.timers.cancel(ElectionTimer)

  private def resetHeartbeat(state: State[T]) = {
    state.timers.cancel(HeartbeatTimer)
    state.timers.startSingleTimer(
      HeartbeatTimer,
      HeartbeatTimeout(),
      FiniteDuration(25, TimeUnit.MILLISECONDS)
    )
  }
  private def stopHeartbeat(state: State[T]) = state.timers.cancel(HeartbeatTimer)
}
