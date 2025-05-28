package raft.cluster

import akka.actor.typed.ActorRef

final class ProcessID(val id: Int) extends Serializable {
  override def hashCode(): Int = this.id
  override def equals(obj: Any): Boolean = obj match {
    case that: ProcessID => this.id == that.id
    case _               => false
  }
}

final class Cluster[T <: Serializable](
    val refs: Map[ProcessID, ActorRef[raft.process.Message[T]]]
) extends Iterable[(ProcessID, ActorRef[raft.process.Message[T]])] {
  def apply(id: ProcessID): ActorRef[raft.process.Message[T]] = this.refs(id)

  def peers(of: ProcessID): Iterator[(ProcessID, ActorRef[raft.process.Message[T]])] =
    this.refs.filter(_._1 != of).iterator

  override def iterator: Iterator[(ProcessID, ActorRef[raft.process.Message[T]])] =
    this.refs.iterator
}
