package client.ticket

sealed trait Message

// Private
private final case class Wait() extends Message
private final case class Timeout() extends Message