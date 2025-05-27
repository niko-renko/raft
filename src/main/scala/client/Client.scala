package client

import raft.ProcessID

sealed trait Message

final case class AppendResponse(
    id: Int,
    success: Boolean,
    leaderId: Option[ProcessID]
) extends Message
final case class ReadResponse(
    success: Boolean,
    value: String
) extends Message
final case class ReadUnstableResponse(
    value: String
) extends Message
