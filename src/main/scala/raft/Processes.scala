package raft

import akka.actor.typed.ActorRef

final class ProcessID(val id: Int) extends Serializable {
  override def hashCode(): Int = this.id
  override def equals(obj: Any): Boolean = obj match {
    case that: ProcessID => this.id == that.id
    case _               => false
  }
}

final class Processes[T](
    val refs: Map[ProcessID, ActorRef[T]]
) extends Iterable[(ProcessID, ActorRef[T])] {
  def getRef(id: ProcessID): ActorRef[T] = this.refs(id)

  def peers(of: ProcessID): Iterator[(ProcessID, ActorRef[T])] =
    this.refs.filter(_._1 != of).iterator

  override def iterator: Iterator[(ProcessID, ActorRef[T])] =
    this.refs.iterator
}
