package raft.process

import akka.actor.typed.ActorRef

import machine.StateMachine
import raft.cluster.{ProcessID, Cluster, ClusterResponse}

enum Role:
  case Follower
  case Candidate
  case Leader

final private case class PendingAppend(
  index: Int,
  term: Int,
  id: Int,
  ref: ActorRef[raft.client.Message]
)

final private case class PendingRead(
  index: Int,
  term: Int,
  value: String,
  ref: ActorRef[raft.client.Message]
)

final private case class State[T <: Serializable](
    self: ProcessID,
    refs: Cluster[T],
    timers: Timer[T],
    lastEntriesTime: Map[ProcessID, Long],

    commitIndex: Int,
    nextIndex: Map[ProcessID, Int],
    matchIndex: Map[ProcessID, Int],
    votes: Int,
    role: Role,
    leaderId: Option[ProcessID],

    appends: List[PendingAppend],
    reads: List[PendingRead],
    requests: Set[Int],

    committed: StateMachine[T, T],
    uncommitted: StateMachine[T, T],

    asleep: Boolean,
    replyToClient: Boolean,
    delayed: List[ClusterResponse[T] | Message[T]]
)
