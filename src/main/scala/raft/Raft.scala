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
    nextIndex: Map[ProcessID, Int],
    matchIndex: Map[ProcessID, Int],
    lastEntriesTime: Map[ProcessID, Long],
    votes: Int,
    role: Role,
    leaderId: Option[ProcessID],
    asleep: Boolean,
    delayed: List[Message[T]],
    pending: List[(Int, Int)]
)

sealed trait Message[T <: Serializable]

// Public
final case class RefsResponse[T <: Serializable](
    refs: Processes[T]
) extends Message[T]
final case class Append[T <: Serializable](
    id: Int,
    entry: T
) extends Message[T]
final case class Crash[T <: Serializable](
) extends Message[T]
final case class Sleep[T <: Serializable](
    seconds: Int
) extends Message[T]

// Private
final private case class ElectionTimeout[T <: Serializable](
) extends Message[T]
final private case class HeartbeatTimeout[T <: Serializable](
) extends Message[T]
final private case class SleepTimeout[T <: Serializable](
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
    lastLogIndex: Int,
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
      context.log.info("Starting")
      parent ! Refs(self)
      Behaviors.receive { (context, message) => message match {
          case RefsResponse(refs) if refs.size % 2 == 0 =>
            Behaviors.stopped
          case RefsResponse(refs) =>
            Behaviors.withTimers(_timers => {
              val timers = new Timers[T](_timers)
              timers.register(timers.Election, ElectionTimeout(), (150, 301))
              timers.register(timers.Heartbeat, HeartbeatTimeout(), (25, 51))
              timers.register(timers.Sleep, SleepTimeout(), (-1, -1))

              val state =
                State(self, refs, parent, timers, 0, Map(), Map(), Map(), 0, Role.Follower, None, false, List(), List())
              val persistent = PersistentState.load[T](self.id)

              state.timers.set(timers.Election)
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
        case _: Append[T] | _: Crash[T] | _: Sleep[T] => context.log.info("{}", message)
        case _ => context.log.trace("{}", message)
      }

      message match {
        case SleepTimeout() => {
            state.delayed.foreach(message => context.self ! message)
            val nstate = state.copy(asleep = false, delayed = List())
            this.main(nstate, persistent)
          }
          case message: Message[T] if state.asleep => {
            val nstate = state.copy(delayed = state.delayed :+ message)
            this.main(nstate, persistent)
          }
          case ElectionTimeout() => {
            val npersistent = persistent.copy(
              term = persistent.term + 1,
              votedFor = Some(state.self)
            )
            npersistent.save(state.self.id)
            val nstate = state.copy(
              role = Role.Candidate,
              votes = 1
            )
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
            state.timers.set(state.timers.Election)
            this.main(nstate, npersistent)
          }
          case HeartbeatTimeout() => {
            val needHeartbeat = state.refs
              .peers(state.self)
              .filter((id, _) => System.currentTimeMillis() - state.lastEntriesTime(id) >= 50)
              .toList

            val nstate = this.replicate(state, persistent, needHeartbeat)
            state.timers.set(state.timers.Heartbeat)
            this.main(nstate, persistent)
          }

          case Append(id, entry) if state.role != Role.Leader => {
            state.parent ! AppendResponse(id, false, state.leaderId)
            this.main(state, persistent)
          }
          case Append(id, entry) => {
            val npersistent = persistent.copy(log = persistent.log :+ (persistent.term, entry))
            npersistent.save(state.self.id)
            context.log.info("Leader Log: {}", npersistent.log)

            val (lastLogIndex, _) = npersistent.last()
            val nstate = this
              .replicate(state, npersistent, state.refs.peers(state.self).toList)
              .copy(pending = state.pending :+ (lastLogIndex, id))

            this.main(nstate, npersistent)
          }

          case Crash() => throw new Exception("DEADBEEF")
          case Sleep(seconds) => {
            state.timers.set(state.timers.Sleep, seconds * 1000)
            val nstate = state.copy(asleep = true, delayed = List())
            this.main(nstate, persistent)
          }

          case AppendEntries(term, leaderId, _, _, _, _) if term < persistent.term => {
            val (lastLogIndex, _) = persistent.last()
            state.refs.getRef(leaderId) ! AppendEntriesResponse(
              persistent.term,
              false,
              lastLogIndex,
              state.self
            )
            this.main(state, persistent)
          }
          case AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit) => {
            val hasPrev = persistent.has(prevLogIndex, prevLogTerm)

            val npersistent = if (!entries.isEmpty && hasPrev && persistent.log.drop(prevLogIndex + 1) != entries) {
              val npersistent = persistent.copy(
                term = term,
                votedFor = if (term > persistent.term) None else persistent.votedFor,
                log = persistent.log.take(prevLogIndex + 1) ++ entries
              )
              npersistent.save(state.self.id)
              context.log.info("Follower Log: {}", npersistent.log)
              npersistent
            } else if (term > persistent.term) {
              val npersistent = persistent.copy(term = term, votedFor = None)
              npersistent.save(state.self.id)
              npersistent
            } else {
              persistent
            }

            val (lastLogIndex, _) = npersistent.last()
            val commitIndex = math.min(leaderCommit, lastLogIndex)

            assert(state.commitIndex <= commitIndex) // Increases monotonically
            if (state.commitIndex < commitIndex) {
              context.log.info("Follower CommitIndex: {}", commitIndex)
            }

            val nstate = if (state.role == Role.Leader || state.role == Role.Candidate) {
              assert(state.role == Role.Candidate || state.role == Role.Leader && term > persistent.term)
              state.copy(role = Role.Follower, leaderId = Some(leaderId), commitIndex = commitIndex)
            } else if (state.leaderId.isEmpty || state.leaderId.get != leaderId) {
              state.copy(leaderId = Some(leaderId), commitIndex = commitIndex)
            } else if (state.commitIndex < commitIndex) {
              state.copy(commitIndex = commitIndex)
            } else {
              state
            }

            nstate.timers.set(state.timers.Election)

            nstate.refs.getRef(leaderId) ! AppendEntriesResponse(
              persistent.term,
              hasPrev,
              lastLogIndex,
              state.self
            )
            this.main(nstate, npersistent)
          }

          case AppendEntriesResponse(term, success, _, _) if term > persistent.term => {
            val npersistent = persistent.copy(term = term, votedFor = None)
            npersistent.save(state.self.id)
            val nstate = state.copy(role = Role.Follower)
            nstate.timers.set(state.timers.Election)
            this.main(nstate, npersistent)
          }
          case AppendEntriesResponse(term, success, _, _) if term < persistent.term || state.role != Role.Leader => this.main(state, persistent)
          case AppendEntriesResponse(term, success, _, process) if !success => {
            val nextIndex = state.nextIndex(process) - 1
            context.log.info("NextIndex: {}", (process -> nextIndex))

            val ref = state.refs.peers(state.self).filter((id, _) => id == process).toList
            val nstate = this.replicate(state.copy(nextIndex = state.nextIndex + (process -> nextIndex)), persistent, ref)
            this.main(nstate, persistent)
          }
          case AppendEntriesResponse(term, success, lastLogIndex, process) => {
            assert(state.matchIndex(process) <= lastLogIndex) // Increases monotonically

            val nstate = if (state.matchIndex(process) < lastLogIndex) {
              val nextIndex = lastLogIndex + 1
              context.log.info("NextIndex: {}", (process -> nextIndex))

              val matchIndex = state.matchIndex + (process -> lastLogIndex)
              val n = matchIndex.values.toList.sorted()(state.refs.size / 2)
              val (nTerm, _) = persistent(n)
              val commitIndex = if (nTerm == persistent.term) n else state.commitIndex
              
              state.copy(
                nextIndex = state.nextIndex + (process -> nextIndex),
                matchIndex = matchIndex,
                commitIndex = commitIndex
              )
            } else {
              state
            }

            assert(state.commitIndex <= nstate.commitIndex) // Increases monotonically
            if (state.commitIndex < nstate.commitIndex) {
              context.log.info("Leader CommitIndex: {}", nstate.commitIndex)
              nstate.pending.filter((index, _) => index <= nstate.commitIndex).foreach((_, id) => state.parent ! AppendResponse(id, true, Some(state.self)))
            }

            this.main(nstate, persistent)
          }

          case RequestVote(term, candidateId, _, _) if term < persistent.term => {
            context.log.info("({}) Denied vote to {}", persistent.term, candidateId)
            state.refs.getRef(candidateId) ! RequestVoteResponse(
              persistent.term,
              false
            )
            this.main(state, persistent)
          }
          case RequestVote(term, candidateId, lastLogIndex, lastLogTerm) => {
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
              nstate.timers.set(state.timers.Election)
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
            val npersistent = persistent.copy(term = term, votedFor = None)
            npersistent.save(state.self.id)
            val nstate = state.copy(role = Role.Follower)
            this.main(nstate, npersistent)
          }
          case RequestVoteResponse(term, voteGranted)
              if term < persistent.term || state.role != Role.Candidate || !voteGranted => this.main(state, persistent)
          case RequestVoteResponse(term, _) if state.votes + 1 <= state.refs.size / 2 => {
            val nstate = state.copy(votes = state.votes + 1)
            this.main(nstate, persistent)
          }
          case RequestVoteResponse(term, _) => {
            context.log.info("({}) Becoming leader", persistent.term)

            val nstate = state.copy(
              role = Role.Leader,
              nextIndex = state.refs.peers(state.self).map((id, ref) => (id, persistent.log.length)).toMap,
              matchIndex = state.refs.peers(state.self).map((id, ref) => (id, 0)).toMap,
              lastEntriesTime = state.refs.peers(state.self).map((id, ref) => (id, 0L)).toMap
            )

            nstate.timers.set(state.timers.Heartbeat, 0)
            this.main(nstate, persistent)
          }

          case _ => Behaviors.stopped
        }
    }

  private def replicate(state: State[T], persistent: PersistentState[T], processes: Iterable[(ProcessID, ActorRef[Message[T]])]): State[T] = {
    processes.foreach((id, ref) => {
      val nextIndex = state.nextIndex(id)
      val prevLogIndex = nextIndex - 1
      val (prevLogTerm, _) = persistent(prevLogIndex)
      ref ! AppendEntries(
        persistent.term,
        state.self,
        prevLogIndex,
        prevLogTerm,
        persistent.from(nextIndex),
        state.commitIndex
      )
    })

    val now = System.currentTimeMillis()
    val newTime = processes.map((id, _) => (id, now)).toMap
    state.copy(lastEntriesTime = state.lastEntriesTime ++ newTime)
  }
}
