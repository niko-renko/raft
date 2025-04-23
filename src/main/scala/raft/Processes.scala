package raft

import akka.actor.typed.ActorRef

final class ProcessID(val id: Int) extends Serializable

final class Processes[T](
    val refs: Map[ProcessID, ActorRef[T]]
) extends Iterable[(ProcessID, ActorRef[T])] {
  def getRef(id: ProcessID): ActorRef[T] = this.refs(id)

  def peers(of: ProcessID): Iterator[(ProcessID, ActorRef[T])] =
    this.refs.filter(_._1 != of).iterator

  override def iterator: Iterator[(ProcessID, ActorRef[T])] =
    this.refs.iterator
}
