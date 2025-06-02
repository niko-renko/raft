package raft.process

import akka.actor.typed.ActorRef

import raft.cluster.ProcessID

sealed trait Message[T <: Serializable]

// Public
final case class Crash[T <: Serializable](
) extends Message[T]
final case class Sleep[T <: Serializable](
  replyToClient: Boolean,
  collect: Boolean
) extends Message[T]
final case class Awake[T <: Serializable](
) extends Message[T]

final case class Read[T <: Serializable](
    ref: ActorRef[raft.client.Message]
) extends Message[T]
final case class ReadUnstable[T <: Serializable](
    ref: ActorRef[raft.client.Message]
) extends Message[T]
final case class Append[T <: Serializable](
    ref: ActorRef[raft.client.Message],
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
    entries: List[Entry[T]],
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

