package client

import raft.ProcessID

sealed trait Message

final case class AppendResponse(
    id: Int,
    success: Boolean,
    leaderId: Option[ProcessID]
) extends Message
final case class ReadCommittedResponse(
    value: String
) extends Message
final case class ReadUncommittedResponse(
    value: String
) extends Message
