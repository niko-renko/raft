package raft

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.ActorContext

import guardian.Refs
import client.{AppendResponse, ReadResponse, ReadUnstableResponse}

enum Role:
  case Follower
  case Candidate
  case Leader

final private case class State[T <: Serializable](
    self: ProcessID,
    refs: Processes[T],
    timers: Timers[T],
    lastEntriesTime: Map[ProcessID, Long],
    pending: List[(Int, Int, ActorRef[client.Message])],
    requests: Set[Int],

    commitIndex: Int,
    nextIndex: Map[ProcessID, Int],
    matchIndex: Map[ProcessID, Int],
    votes: Int,
    role: Role,
    leaderId: Option[ProcessID],

    committed: StateMachine[T, T],
    uncommitted: StateMachine[T, T],

    asleep: Boolean,
    collect: Boolean,
    delayed: List[Message[T]]
)

sealed trait Message[T <: Serializable]

// Public
final case class RefsResponse[T <: Serializable](
    refs: Processes[T]
) extends Message[T]

final case class Crash[T <: Serializable](
) extends Message[T]
final case class Sleep[T <: Serializable](
  collect: Boolean
) extends Message[T]
final case class Awake[T <: Serializable](
) extends Message[T]

final case class Read[T <: Serializable](
    ref: ActorRef[client.Message]
) extends Message[T]
final case class ReadUnstable[T <: Serializable](
    ref: ActorRef[client.Message]
) extends Message[T]
final case class Append[T <: Serializable](
    ref: ActorRef[client.Message],
    id: Int,
    entry: T
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
    entries: List[(Int, Int, T)],
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
  def apply(self: ProcessID, parent: ActorRef[guardian.Message], machine: StateMachine[T, T]): Behavior[Message[T]] =
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

              val persistent = PersistentState.load[T](self.id)
              val requests = Set() ++ persistent.log.map(_._2)

              val state =
                State(
                  self, // Self
                  refs, // Refs
                  timers, // Timers 
                  Map(), // Last Entries Time
                  List(), // Pending
                  requests, // Requests

                  0, // Commit Index
                  Map(), // Next Index
                  Map(), // Match Index
                  0, // Votes
                  Role.Follower, // Role
                  None, // Leader ID

                  machine.copy(), // Committed
                  machine.copy(), // Uncommitted

                  false, // Asleep
                  false, // Collect
                  List() // Delayed
                )

              state.timers.set(state.timers.Election)
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
        case _: Read[T] | _: ReadUnstable[T] | _: Append[T] => context.log.info("{}", message)
        case _: Crash[T] | _: Sleep[T] | _: Awake[T] => context.log.info("{}", message)
        case _ => context.log.trace("{}", message)
      }

      message match {
        // ----- Public Log -----
        case Read(ref) => {
          // Effects
          if (state.commitIndex == 0)
            ref ! ReadResponse(false, "null")
          else
            ref ! ReadResponse(true, state.committed.state().toString)

          this.main(state, persistent)
        }
        case ReadUnstable(ref) => {
          // Effects
          if (persistent.log.size == 1)
            ref ! ReadUnstableResponse(false, "null")
          else
            ref ! ReadUnstableResponse(true, state.uncommitted.state().toString)

          this.main(state, persistent)
        }
        case Append(ref, id, entry) if state.role != Role.Leader || state.requests.contains(id) => {
          // Effects
          ref ! AppendResponse(id, false, state.leaderId)

          this.main(state, persistent)
        }
        case Append(ref, id, entry) => {
          // Update State
          val npersistent = persistent.copy(
            log = persistent.log :+ (persistent.term, id, entry)
          )
          npersistent.save(state.self.id)
          val (lastLogIndex, _) = npersistent.last()
          val nstate = this
            .replicate(state, npersistent, state.refs.peers(state.self).toList)
            .copy(
              pending = state.pending :+ (lastLogIndex, id, ref),
              requests = state.requests + id
            )
          
          // Effects
          state.uncommitted.apply(entry)

          this.info(context, state, nstate, persistent, npersistent)
          this.main(nstate, npersistent)
        }
        // ----- Public Status -----
        case Crash() => throw new Exception("DEADBEEF")
        case Sleep(collect) => {
          // Update State
          val nstate = state.copy(
            asleep = true,
            collect = collect,
            delayed = List()
          )

          this.info(context, state, nstate, persistent, persistent)
          this.main(nstate, persistent)
        }
        case Awake() => {
          // Update State
          val nstate = state.copy(
            asleep = false,
            collect = false,
            delayed = List()
          )

          // Effects
          state.delayed.foreach(message => context.self ! message)
          
          this.info(context, state, nstate, persistent, persistent)
          this.main(nstate, persistent)
        }
        case message: Message[T] if state.asleep => {
          // Update State
          val nstate = if (state.collect)
            state.copy(
              delayed = state.delayed :+ message
            )
          else
            state

          this.info(context, state, nstate, persistent, persistent)
          this.main(nstate, persistent)
        }
        // ----- Private Raft -----
        case ElectionTimeout() => {
          // Update State
          val npersistent = persistent.copy(
            term = persistent.term + 1,
            votedFor = Some(state.self)
          )
          npersistent.save(state.self.id)
          val nstate = state.copy(
            role = Role.Candidate,
            votes = 1
          )

          // Effects
          val (lastLogIndex, lastLogTerm) = persistent.last()
          state.refs
            .peers(nstate.self)
            .foreach((id, ref) =>
              ref ! RequestVote(
                npersistent.term,
                state.self,
                lastLogIndex,
                lastLogTerm
              )
            )
          state.timers.set(state.timers.Election)
          
          this.info(context, state, nstate, persistent, npersistent)
          this.main(nstate, npersistent)
        }
        case HeartbeatTimeout() => {
          // Update State
          val now = System.currentTimeMillis()
          val needHeartbeat = state.refs
            .peers(state.self)
            .filter((id, _) => now - state.lastEntriesTime(id) < 0 || now - state.lastEntriesTime(id) >= 50)
            .toList
          val nstate = if (!needHeartbeat.isEmpty) 
            this.replicate(state, persistent, needHeartbeat)
          else
            state

          // Effects
          state.timers.set(state.timers.Heartbeat)

          this.info(context, state, nstate, persistent, persistent)
          this.main(nstate, persistent)
        }

        case AppendEntries(term, leaderId, _, _, _, _) if term < persistent.term => {
          // Effects
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
          // Update State
          val hasPrev = persistent.has(prevLogIndex, prevLogTerm)
          val npersistent = persistent.copy(
            term = term,
            votedFor = if (term > persistent.term) None else persistent.votedFor,
            log = if (hasPrev) persistent.log.take(prevLogIndex + 1) ++ entries else persistent.log
          )
          npersistent.save(state.self.id)

          val (lastLogIndex, _) = npersistent.last()
          val commitIndex = math.min(leaderCommit, lastLogIndex)
          val removed = persistent.log.drop(lastLogIndex + 1).map(_._2)
          val added = entries.map(_._2)
          val nstate = state.copy(
            role = Role.Follower,
            leaderId = Some(leaderId),
            commitIndex = commitIndex,
            requests = if (hasPrev) state.requests -- removed ++ added else state.requests,
            uncommitted = state.committed.copy()
          )

          // Effects
          assert(state.role != Role.Leader || npersistent.term > persistent.term)
          assert(state.commitIndex <= nstate.commitIndex) // Increases monotonically

          if (state.commitIndex < nstate.commitIndex)
            npersistent.log
              .drop(state.commitIndex + 1)
              .take(nstate.commitIndex - state.commitIndex)
              .map(_._3)
              .foreach(state.committed.apply)

          npersistent.log
            .drop(state.commitIndex + 1)
            .map(_._3)
            .foreach(state.uncommitted.apply)

          state.timers.set(state.timers.Election)
          state.refs.getRef(leaderId) ! AppendEntriesResponse(
            npersistent.term,
            hasPrev,
            lastLogIndex,
            state.self
          )

          this.info(context, state, nstate, persistent, npersistent)
          this.main(nstate, npersistent)
        }

        case AppendEntriesResponse(term, success, _, _) if term > persistent.term => {
          // Update State
          val npersistent = persistent.copy(
            term = term,
            votedFor = None
          )
          npersistent.save(state.self.id)
          val nstate = state.copy(
            role = Role.Follower
          )

          // Effects
          state.timers.set(state.timers.Election)

          this.info(context, state, nstate, persistent, npersistent)
          this.main(nstate, npersistent)
        }
        case AppendEntriesResponse(term, success, _, _) if term < persistent.term || state.role != Role.Leader => this.main(state, persistent)
        case AppendEntriesResponse(term, success, _, process) if !success => {
          // Update State
          val ref = state.refs.peers(state.self).filter((id, _) => id == process).toList
          val nstate = this.replicate(
            state.copy(
              nextIndex = state.nextIndex + (process -> (state.nextIndex(process) - 1))
            ),
            persistent,
            ref
          )

          this.info(context, state, nstate, persistent, persistent)
          this.main(nstate, persistent)
        }
        case AppendEntriesResponse(term, success, lastLogIndex, process) => {
          // Update State
          val matchIndex = state.matchIndex + (process -> lastLogIndex)
          val n = matchIndex.values.toList.sorted()(state.refs.size / 2)
          val (nTerm, _, _) = persistent(n)
          val commitIndex = if (nTerm == persistent.term) n else state.commitIndex

          val nstate = state.copy(
            nextIndex = state.nextIndex + (process -> (lastLogIndex + 1)),
            matchIndex = matchIndex,
            commitIndex = commitIndex,
            pending = state.pending.filter((index, _, _) => index > commitIndex)
          )

          // Effects
          assert(state.matchIndex(process) <= nstate.matchIndex(process)) // Increases monotonically
          assert(state.commitIndex <= nstate.commitIndex) // Increases monotonically

          if (state.commitIndex < nstate.commitIndex) {
            persistent.log
              .drop(state.commitIndex + 1)
              .take(nstate.commitIndex - state.commitIndex)
              .map(_._3)
              .foreach(state.committed.apply)

            state.pending
              .filter((index, _, _) => index <= nstate.commitIndex)
              .foreach((_, id, ref) => ref ! AppendResponse(id, true, Some(state.self)))
          }

          this.info(context, state, nstate, persistent, persistent)
          this.main(nstate, persistent)
        }

        case RequestVote(term, candidateId, _, _) if term < persistent.term => {
          // Effects
          state.refs.getRef(candidateId) ! RequestVoteResponse(
            persistent.term,
            false
          )

          this.main(state, persistent)
        }
        case RequestVote(term, candidateId, lastLogIndex, lastLogTerm) => {
          // Update State
          val (thisLastLogIndex, thisLastLogTerm) = persistent.last()
          val upToDate = lastLogTerm > thisLastLogTerm || (lastLogTerm == thisLastLogTerm && lastLogIndex >= thisLastLogIndex)
          val decision = (term > persistent.term || (term == persistent.term && candidateId == persistent.votedFor.get)) && upToDate

          val votedFor = if (decision)
            Some(candidateId)
          else if (term > persistent.term)
            None
          else persistent.votedFor

          val npersistent = persistent.copy(
            term = term,
            votedFor = votedFor
          )
          npersistent.save(state.self.id)

          val nstate = state.copy(
            role = Role.Follower
          )

          // Effects
          if (npersistent.term > persistent.term && npersistent.votedFor.isDefined)
            state.timers.set(state.timers.Election)

          state.refs.getRef(candidateId) ! RequestVoteResponse(
            npersistent.term,
            decision
          )

          this.info(context, state, nstate, persistent, npersistent)
          this.main(nstate, npersistent)
        }

        case RequestVoteResponse(term, _) if term > persistent.term => {
          // Update State
          val npersistent = persistent.copy(
            term = term,
            votedFor = None
          )
          npersistent.save(state.self.id)
          val nstate = state.copy(
            role = Role.Follower
          )

          this.info(context, state, nstate, persistent, npersistent)
          this.main(nstate, npersistent)
        }
        case RequestVoteResponse(term, voteGranted)
            if term < persistent.term || state.role != Role.Candidate || !voteGranted => this.main(state, persistent)
        case RequestVoteResponse(term, _) if state.votes + 1 <= state.refs.size / 2 => {
          // Update State
          val nstate = state.copy(
            votes = state.votes + 1
          )

          this.info(context, state, nstate, persistent, persistent)
          this.main(nstate, persistent)
        }
        case RequestVoteResponse(term, _) => {
          // Update State
          val nstate = state.copy(
            role = Role.Leader,
            nextIndex = state.refs.peers(state.self).map((id, ref) => (id, persistent.log.length)).toMap,
            matchIndex = state.refs.peers(state.self).map((id, ref) => (id, 0)).toMap,
            lastEntriesTime = state.refs.peers(state.self).map((id, ref) => (id, 0L)).toMap
          )

          // Effects
          state.timers.set(state.timers.Heartbeat, 0)

          this.info(context, state, nstate, persistent, persistent)
          this.main(nstate, persistent)
        }

        case _ => Behaviors.stopped
      }
    }

  private def replicate(state: State[T], persistent: PersistentState[T], processes: Iterable[(ProcessID, ActorRef[Message[T]])]): State[T] = {
    processes.foreach((id, ref) => {
      val nextIndex = state.nextIndex(id)
      val prevLogIndex = nextIndex - 1
      val (prevLogTerm, _, _) = persistent(prevLogIndex)
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
    state.copy(
      lastEntriesTime = state.lastEntriesTime ++ newTime
    )
  }

  private def info(
    context: ActorContext[Message[T]],
    state: State[T],
    nstate: State[T],
    persistent: PersistentState[T],
    npersistent: PersistentState[T]
  ): Unit = {
    if (nstate.role != state.role)
      context.log.info("({}) [{}] NewRole", npersistent.term, nstate.role)

    if (nstate.commitIndex > state.commitIndex)
      context.log.info("({}) [{}] CommitIndex: {}", npersistent.term, nstate.role, nstate.commitIndex)

    if (persistent.term != npersistent.term || npersistent.votedFor != persistent.votedFor)
      context.log.info("({}) [{}] VotedFor: {}", npersistent.term, nstate.role, npersistent.votedFor)

    if (npersistent.log != persistent.log)
      context.log.info("({}) [{}] Log: {}", npersistent.term, nstate.role, npersistent.log)
  }
}
