package client.text

sealed trait Message

// Public
final case class Control(
    command: String
) extends Message
