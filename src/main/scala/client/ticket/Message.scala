package client.ticket

sealed trait Message

final case class Count(
) extends Message
final case class Book(
    count: Int
) extends Message
