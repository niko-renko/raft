package raft.cluster

import akka.actor.typed.ActorRef

sealed trait Message[T <: Serializable]

// Public
final case class Refs[T <: Serializable](ref: ActorRef[raft.process.Message[T]]) extends Message[T]
final case class Control[T <: Serializable](command: String) extends Message[T]