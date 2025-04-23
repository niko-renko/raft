package raft

import akka.actor.typed.ActorRef

final class ProcessID(val id: Int) extends Serializable

final class Processes[T <: Serializable](
    val refs: Map[ProcessID, ActorRef[Message[T]]]
) extends Iterable[(ProcessID, ActorRef[Message[T]])] {
  def getRef(id: ProcessID): ActorRef[Message[T]] = this.refs(id)

  def peers(of: ProcessID): Iterator[(ProcessID, ActorRef[Message[T]])] =
    this.refs.filter(_._1 != of).iterator

  override def iterator: Iterator[(ProcessID, ActorRef[Message[T]])] =
    this.refs.iterator
}
