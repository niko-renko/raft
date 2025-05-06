package raft

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import guardian.{Refs, AppendResponse}

enum Role:
  case Follower
  case Candidate
  case Leader

final private case class State[T <: Serializable](
    self: ProcessID,
    refs: Processes[T],
    parent: ActorRef[guardian.Message],
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
final case class RefsResponse[T <: Serializable](
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
    entries: List[(Int, T)],
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
      parent ! Refs(self)
      Behaviors.receive { (context, message) => message match {
          case RefsResponse(refs) if refs.size % 2 == 0 =>
            Behaviors.stopped
          case RefsResponse(refs) =>
            Behaviors.withTimers(_timers => {
              val timers = new Timers[T](_timers)
              timers.register(Election, ElectionTimeout(), (150, 301))
              timers.register(Heartbeat, HeartbeatTimeout(), (25, 51))
              timers.register(Pause, PauseTimeout(), (-1, -1))

              val state =
                State(self, refs, parent, timers, 0, 0, Map(), Map(), 0, Role.Follower, false)
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

            val (lastLogIndex, lastLogTerm) = npersistent.last()
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
            val (lastLogIndex, lastLogTerm) = persistent.last()
            context.log.trace("HeartbeatTimeout ({}, {})", lastLogIndex, lastLogTerm)
            state.refs
              .peers(state.self)
              .foreach((id, ref) =>
                ref ! AppendEntries(
                  persistent.term,
                  state.self,
                  lastLogIndex,
                  lastLogTerm,
                  List(),
                  state.commitIndex
                )
              )
            state.timers.set(Heartbeat)
            this.main(state, persistent)
          }

          case Append(entries) => {
            context.log.info("Received Append for {}", entries)
            state.parent ! AppendResponse(false)
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
            context.log.trace("({}) Received AppendEntries", term)
            state.refs.getRef(leaderId) ! AppendEntriesResponse(
              persistent.term,
              false
            )
            this.main(state, persistent)
          }
          case AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit) => {
            context.log.trace("({}) Received AppendEntries", term)
            val hasPrev = persistent.has(prevLogIndex, prevLogTerm)

            val npersistent = if (!entries.isEmpty && hasPrev) {
              val npersistent = persistent.copy(
                term = term,
                votedFor = if (term > persistent.term) None else persistent.votedFor,
                log = persistent.log.take(prevLogIndex + 1) ++ entries
              )
              npersistent.save(state.self.id)
              npersistent
            } else if (term > persistent.term) {
              val npersistent = persistent.copy(term = term, votedFor = None)
              npersistent.save(state.self.id)
              npersistent
            } else {
              persistent
            }

            // TODO: Commit index
            val nstate = if (state.role == Role.Leader || state.role == Role.Candidate) {
              assert(state.role == Role.Candidate || state.role == Role.Leader && term > persistent.term)
              state.copy(role = Role.Follower)
            } else {
              state
            }

            nstate.timers.set(Election)

            nstate.refs.getRef(leaderId) ! AppendEntriesResponse(
              persistent.term,
              hasPrev
            )
            this.main(nstate, npersistent)
          }

          case AppendEntriesResponse(term, success) if term < persistent.term || state.role != Role.Leader => {
            context.log.trace("({}) Received AppendEntriesResponse {}", term, success)
            this.main(state, persistent)
          }
          case AppendEntriesResponse(term, success) if term > persistent.term => {
            context.log.trace("({}) Received AppendEntriesResponse {}", term, success)
            val npersistent = persistent.copy(term = term, votedFor = None)
            npersistent.save(state.self.id)
            val nstate = state.copy(role = Role.Follower)
            nstate.timers.set(Election)
            this.main(nstate, npersistent)
          }
          case AppendEntriesResponse(term, success) => {
            context.log.trace("({}) Received AppendEntriesResponse {}", term, success)
            this.main(state, persistent)
          }

          case RequestVote(term, candidateId, _, _) if term < persistent.term => {
            context.log.trace("({}) Received RequestVote", term)
            context.log.info("({}) Denied vote to {}", persistent.term, candidateId)
            state.refs.getRef(candidateId) ! RequestVoteResponse(
              persistent.term,
              false
            )
            this.main(state, persistent)
          }
          case RequestVote(term, candidateId, lastLogIndex, lastLogTerm) => {
            context.log.trace("({}) Received RequestVote", term)
            val (thisLastLogIndex, thisLastLogTerm) = persistent.last()
            val upToDate = lastLogTerm > thisLastLogTerm || (lastLogTerm == thisLastLogTerm && lastLogIndex >= thisLastLogIndex)
            val decision = (term > persistent.term || (term == persistent.term && candidateId == persistent.votedFor.get)) && upToDate

            val npersistent = if (decision) {
              val npersistent = persistent.copy(term = term, votedFor = Some(candidateId))
              npersistent.save(state.self.id)
              npersistent
            } else if (term > persistent.term) {
              val npersistent = persistent.copy(term = term, votedFor = None)
              npersistent.save(state.self.id)
              npersistent
            } else {
              persistent
            }
            
            val nstate = if (term > persistent.term && (state.role == Role.Leader || state.role == Role.Candidate)) {
              state.copy(role = Role.Follower)
            } else {
              state
            }

            if (decision) {
              context.log.info("({}) Granted vote to {}", npersistent.term, candidateId)
              nstate.timers.set(Election)
            } else {
              context.log.info("({}) Denied vote to {}", npersistent.term, candidateId)
            }

            nstate.refs.getRef(candidateId) ! RequestVoteResponse(
              npersistent.term,
              decision
            )
            this.main(nstate, npersistent)
          }

          case RequestVoteResponse(term, voteGranted)
              if term < persistent.term || state.role != Role.Candidate || !voteGranted => {
            context.log.trace("({}) Received RequestVoteResponse", term)
            this.main(state, persistent)
          }
          case RequestVoteResponse(term, _) if term > persistent.term => {
            context.log.trace("({}) Received RequestVoteResponse", term)
            val npersistent = persistent.copy(term = term, votedFor = None)
            npersistent.save(state.self.id)
            val nstate = state.copy(role = Role.Follower)
            this.main(nstate, npersistent)
          }
          case RequestVoteResponse(term, _) if state.votes + 1 <= state.refs.size / 2 => {
            context.log.trace("({}) Received RequestVoteResponse", term)
            context.log.info("({}) Received a vote", persistent.term)
            val nstate = state.copy(votes = state.votes + 1)
            this.main(nstate, persistent)
          }
          case RequestVoteResponse(term, _) => {
            context.log.trace("({}) Received RequestVoteResponse", term)
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
}
