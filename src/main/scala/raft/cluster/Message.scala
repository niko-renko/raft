package raft.cluster

import akka.actor.typed.ActorRef

sealed trait Message

// Public
final case class Refs(ref: ActorRef[raft.process.Message]) extends Message
final case class Control(command: String) extends Message