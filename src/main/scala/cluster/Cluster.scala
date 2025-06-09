package cluster

import akka.actor.typed.ActorRef

final class ProcessID(val id: Int) extends Serializable {
  override def hashCode(): Int = this.id
  override def equals(obj: Any): Boolean = obj match {
    case that: ProcessID => this.id == that.id
    case _               => false
  }
}

final class Cluster[T <: Serializable](
    val refs: Map[ProcessID, ActorRef[raft.Message[T]]]
) extends Iterable[(ProcessID, ActorRef[raft.Message[T]])] {
  def apply(id: ProcessID): ActorRef[raft.Message[T]] = this.refs(id)

  def peers(
      of: ProcessID
  ): Iterator[(ProcessID, ActorRef[raft.Message[T]])] =
    this.refs.filter(_._1 != of).iterator

  override def iterator: Iterator[(ProcessID, ActorRef[raft.Message[T]])] =
    this.refs.iterator
}
