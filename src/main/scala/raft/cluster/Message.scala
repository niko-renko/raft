package raft.cluster

import raft.process.ProcessID

sealed trait Message

// Public
final case class Refs(process: ProcessID) extends Message
final case class Control(command: String) extends Message