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
      // Will never dereference null!
      return PersistentState(0, None, List((0, 0, null.asInstanceOf[T])))
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
    log: List[(Int, Int, T)]
) extends Serializable {
  def save(id: Int): Unit = {
    val filename = s"persistent-state/${id}.state"
    Files.createDirectories(Paths.get(filename).getParent)
    val stream = new ObjectOutputStream(new FileOutputStream(filename))
    stream.writeObject(this)
    stream.close()
    ()
  }

  def has(logIndex: Int, logTerm: Int): Boolean = {
    if (logIndex < 0 || logIndex >= this.log.length) {
      false
    } else {
      val (thisLogTerm, _, _) = this.log(logIndex)
      thisLogTerm == logTerm
    }
  }

  def last(): (Int, Int) = {
    val lastLogIndex = this.log.length - 1
    val (lastLogTerm, _, _) = this.log(lastLogIndex)
    (lastLogIndex, lastLogTerm)
  }

  def apply(index: Int): (Int, Int, T) = this.log(index)

  def from(index: Int): List[(Int, Int, T)] =
    this.log.slice(index, this.log.length)
}
