package raft

import java.io.{
  ObjectOutputStream,
  FileOutputStream,
  ObjectInputStream,
  FileInputStream
}
import java.nio.file.{Files, Paths}

object PersistentState {
  def load[T <: Serializable](id: Int): PersistentState[T] = {
    val filename = s"persistent-state/${id}.state"
    if (!Files.exists(Paths.get(filename))) {
      return PersistentState(0, None, List())
    }
    val stream = new ObjectInputStream(new FileInputStream(filename))
    val persistentState = stream.readObject().asInstanceOf[PersistentState[T]]
    stream.close()
    persistentState
  }
}

final case class PersistentState[T <: Serializable](
    term: Int,
    votedFor: Option[ProcessID],
    log: List[(Int, T)]
) extends Serializable {
  def save(id: Int): Unit = {
    val filename = s"persistent-state/${id}.state"
    Files.createDirectories(Paths.get(filename).getParent)
    val stream = new ObjectOutputStream(new FileOutputStream(filename))
    stream.writeObject(this)
    stream.close()
    ()
  }
}
