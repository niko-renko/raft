package client

import raft.ProcessID

sealed trait Message

final case class AppendResponse(
    id: Int,
    success: Boolean,
    leaderId: Option[ProcessID]
) extends Message
final case class ReadResponse[T](value: T) extends Message
