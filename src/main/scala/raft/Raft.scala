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
    lastEntriesTime: Map[ProcessID, Long],
    votes: Int,
    role: Role,
    leaderId: Option[ProcessID],
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
    success: Boolean,
    process: ProcessID
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
                State(self, refs, parent, timers, 0, 0, Map(), Map(), Map(), 0, Role.Follower, None, false)
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
            context.log.info("PauseTimeout")
            val nstate = state.copy(paused = false)
            this.main(nstate, persistent)
          }
          case _ if state.paused => Behaviors.unhandled
          case ElectionTimeout() => {
            context.log.trace("ElectionTimeout")
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
            context.log.trace("HeartbeatTimeout")

            val needHeartbeat = state.lastEntriesTime
              .filter((_, lastEntryTime) => System.currentTimeMillis() - lastEntryTime >= 50)

            val nstate = if (needHeartbeat.size > 0) {
              val now = System.currentTimeMillis()
              val newTime = needHeartbeat.map((id, _) => (id, now))
              val nstate = state.copy(lastEntriesTime = state.lastEntriesTime ++ newTime)

              needHeartbeat.foreach((id, _) => nstate.refs.getRef(id) ! this.appendEntriesMessage(nstate, persistent, id))
              nstate
            } else {
              state
            }

            state.timers.set(Heartbeat)
            this.main(nstate, persistent)
          }

          case Append(entries) if state.role != Role.Leader => {
            context.log.trace("Received Append for {}", entries)
            state.parent ! AppendResponse(false, state.leaderId)
            this.main(state, persistent)
          }
          case Append(entries) => {
            context.log.trace("Received Append for {}", entries)
            val termEntries = entries.map(entry => (persistent.term, entry))
            val npersistent = persistent.copy(log = persistent.log ++ termEntries)
            npersistent.save(state.self.id)
            // TODO: reply when committed
            // state.parent ! AppendResponse(true, Some(state.self))
            this.main(state, npersistent)
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
              false,
              state.self
            )
            this.main(state, persistent)
          }
          case AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit) => {
            context.log.trace("({}) Received AppendEntries", term)
            val hasPrev = persistent.has(prevLogIndex, prevLogTerm)

            val npersistent = if (!entries.isEmpty && hasPrev) {
              context.log.info("Appending entries {}", entries)
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
              state.copy(role = Role.Follower, leaderId = Some(leaderId))
            } else if (state.leaderId.isEmpty || state.leaderId.get != leaderId) {
              state.copy(leaderId = Some(leaderId))
            } else {
              state
            }

            nstate.timers.set(Election)

            nstate.refs.getRef(leaderId) ! AppendEntriesResponse(
              persistent.term,
              hasPrev,
              state.self
            )
            this.main(nstate, npersistent)
          }

          case AppendEntriesResponse(term, success, _) if term > persistent.term => {
            context.log.trace("({}) Received AppendEntriesResponse {}", term, success)
            val npersistent = persistent.copy(term = term, votedFor = None)
            npersistent.save(state.self.id)
            val nstate = state.copy(role = Role.Follower)
            nstate.timers.set(Election)
            this.main(nstate, npersistent)
          }
          case AppendEntriesResponse(term, success, _) if term < persistent.term || state.role != Role.Leader => {
            context.log.trace("({}) Received AppendEntriesResponse {}", term, success)
            this.main(state, persistent)
          }
          case AppendEntriesResponse(term, success, process) if success => {
            context.log.trace("({}) Received AppendEntriesResponse {}", term, success)
            val (lastLogIndex, lastLogTerm) = persistent.last()
            val nstate = if (state.nextIndex(process) < lastLogIndex + 1) {
              context.log.info("({}) Replicated", persistent.term)
              state.copy(nextIndex = state.nextIndex + (process -> (lastLogIndex + 1)))
            } else {
              state
            }
            this.main(nstate, persistent)
          }
          case AppendEntriesResponse(term, success, process) => {
            context.log.trace("({}) Received AppendEntriesResponse {}", term, success)
            context.log.info("({}) Inconsistency", persistent.term)
            val newNextIndex = state.nextIndex(process) - 1
            val now = System.currentTimeMillis()
            val nstate = state.copy(nextIndex = state.nextIndex + (process -> newNextIndex), lastEntriesTime = state.lastEntriesTime + (process -> now))
            nstate.refs.getRef(process) ! this.appendEntriesMessage(nstate, persistent, process)
            this.main(nstate, persistent)
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

          case RequestVoteResponse(term, _) if term > persistent.term => {
            context.log.trace("({}) Received RequestVoteResponse", term)
            val npersistent = persistent.copy(term = term, votedFor = None)
            npersistent.save(state.self.id)
            val nstate = state.copy(role = Role.Follower)
            this.main(nstate, npersistent)
          }
          case RequestVoteResponse(term, voteGranted)
              if term < persistent.term || state.role != Role.Candidate || !voteGranted => {
            context.log.trace("({}) Received RequestVoteResponse", term)
            this.main(state, persistent)
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
              lastEntriesTime = state.refs.peers(state.self).map((id, ref) => (id, 0L)).toMap
            )

            nstate.timers.set(Heartbeat, 0)
            this.main(nstate, persistent)
          }

          case _ => Behaviors.stopped
        }
    }

  private def appendEntriesMessage(state: State[T], persistent: PersistentState[T], id: ProcessID): AppendEntries[T] = {
    val nextIndex = state.nextIndex(id)
    val prevLogIndex = nextIndex - 1
    val (prevLogTerm, _) = persistent(prevLogIndex)
    AppendEntries(
      persistent.term,
      state.self,
      prevLogIndex,
      prevLogTerm,
      persistent.from(nextIndex),
      state.commitIndex
    )
  }
}
