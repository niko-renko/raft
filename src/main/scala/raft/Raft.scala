package raft

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import guardian.GetRefs

enum Role:
  case Follower
  case Candidate
  case Leader

final private case class State[T <: Serializable](
    self: ProcessID,
    refs: Processes[T],
    timers: Timers[T],
    commitIndex: Int,
    lastApplied: Int,
    nextIndex: Map[ProcessID, Int],
    matchIndex: Map[ProcessID, Int],
    votes: Int,
    role: Role,
    paused: Boolean
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
final case class Slow[T <: Serializable](
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

final class Process[T <: Serializable] {
  def apply(self: ProcessID, parent: ActorRef[guardian.Message]): Behavior[Message[T]] =
    Behaviors.setup { context => 
      context.log.info("Starting process {}", self.id)
      parent ! GetRefs(self)
      Behaviors.receive { (context, message) => message match {
          case Refs(refs) if refs.size % 2 == 0 =>
            Behaviors.stopped
          case Refs(refs) =>
            Behaviors.withTimers(_timers => {
              val timers = new Timers[T](_timers)
              timers.register(Election, ElectionTimeout(), (150, 300))
              timers.register(Heartbeat, HeartbeatTimeout(), (25, 50))
              timers.register(Pause, PauseTimeout(), (-1, -1))

              val state =
                State(self, refs, timers, 0, 0, Map(), Map(), 0, Role.Follower, false)
              val persistent = PersistentState.load[T](self.id)

              state.timers.set(Election)
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
          case PauseTimeout() => {
            context.log.info("Resuming process {}", state.self)
            val nstate = state.copy(paused = false)
            this.main(nstate, persistent)
          }
          case _ if state.paused => Behaviors.unhandled
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
            state.timers.set(Election)
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
            state.timers.set(Heartbeat)
            this.main(state, persistent)
          }

          case Append(entries) => {
            context.log.info("Received Append for {}", entries)
            this.main(state, persistent)
          }
          case Crash() => {
            context.log.info("Crashing process {}", state.self)
            throw new Exception("Crashing process")
          }
          case Slow(seconds) => {
            context.log.info("Pausing process {} for {} seconds", state.self, seconds)
            state.timers.set(Pause, seconds * 1000)
            val nstate = state.copy(paused = true)
            this.main(nstate, persistent)
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
                state.copy(role = Role.Follower)
              }
              case Role.Candidate => {
                context.log.info("({}) Becoming follower", npersistent.term)
                state.copy(role = Role.Follower)
              }
              case Role.Follower => {
                state
              }
            }

            state.timers.set(Election)
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
            state.timers.set(Election)
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
          case RequestVoteResponse(term, _) if state.votes + 1 <= state.refs.size / 2 => {
            context.log.info("({}) Received a vote", persistent.term)
            val nstate = state.copy(votes = state.votes + 1)
            this.main(nstate, persistent)
          }
          case RequestVoteResponse(term, _) => {
            context.log.info("({}) Received a vote", persistent.term)
            context.log.info("({}) Becoming leader", persistent.term)

            val nstate = state.copy(
              role = Role.Leader,
              nextIndex = state.refs.peers(state.self).map((id, ref) => (id, persistent.log.length)).toMap,
              matchIndex = state.refs.peers(state.self).map((id, ref) => (id, 0)).toMap,
            )

            state.timers.set(Heartbeat)
            this.main(nstate, persistent)
          }

          case _ => Behaviors.stopped
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
}
