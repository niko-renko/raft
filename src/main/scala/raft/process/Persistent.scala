package raft.process

import java.io.{
  ObjectOutputStream,
  FileOutputStream,
  ObjectInputStream,
  FileInputStream
}
import java.nio.file.{Files, Paths}

import raft.cluster.{ProcessID}

object PersistentState {
  def load[T <: Serializable](id: Int): PersistentState[T] = {
    val filename = s"persistent-state/${id}.state"
    if (!Files.exists(Paths.get(filename))) {
      // Will never dereference null!
      val entry = Entry.Value(0, 0, null.asInstanceOf[T])
      return PersistentState(0, None, List(entry))
    }
    val stream = new ObjectInputStream(new FileInputStream(filename))
    val persistentState = stream.readObject().asInstanceOf[PersistentState[T]]
    stream.close()
    persistentState
  }
}

enum Entry[T <: Serializable]:
  case Read(term: Int, n: Option[T])
  case Value(term: Int, id: Int, value: T)

extension [T <: Serializable](entries: List[Entry[T]]) {
  def terms(): List[Int] = 
    entries.map {
      case Entry.Value(term, _, _) => term
      case Entry.Read(term, _) => term
    }

  def values(): List[(Int, Int, T)] = 
    entries
      .filter {
        case Entry.Value(_, _, _) => true;
        case _ => false
      }
      .map {
        case Entry.Value(term, id, value) => (term, id, value)
        case _ => throw new Exception("Unreachable")
      }
}

final case class PersistentState[T <: Serializable](
    term: Int,
    votedFor: Option[ProcessID],
    log: List[Entry[T]]
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
    if (logIndex < 0 || logIndex >= this.log.length)
      false
    else
      this.log.terms()(logIndex) == logTerm
  }

  def last(): (Int, Int) = {
    val lastLogIndex = this.log.length - 1
    val lastLogTerm = this.log.terms()(lastLogIndex)
    (lastLogIndex, lastLogTerm)
  }

  def from(index: Int): List[Entry[T]] =
    this.log.slice(index, this.log.length)
}
