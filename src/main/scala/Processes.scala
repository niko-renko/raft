import akka.actor.typed.ActorRef

final class ProcessID(val id: Int) extends Serializable

final class Processes(val refs: Map[ProcessID, ActorRef[Process.Message]])
    extends Iterable[(ProcessID, ActorRef[Process.Message])] {
  def getRef(id: ProcessID): ActorRef[Process.Message] = this.refs(id)

  def peers(of: ProcessID): Iterator[(ProcessID, ActorRef[Process.Message])] =
    this.refs.filter(_._1 != of).iterator

  override def iterator: Iterator[(ProcessID, ActorRef[Process.Message])] =
    this.refs.iterator
}
