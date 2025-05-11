package raft

sealed trait StateMachine[T <: Serializable, S] {
  def apply(value: T): Unit
  def state(): S
}

final class LastValue[T <: Serializable] extends StateMachine[T, T] {
  private var value: T = null.asInstanceOf[T]
  override def apply(value: T): Unit = this.value = value
  override def state(): T = this.value
}
