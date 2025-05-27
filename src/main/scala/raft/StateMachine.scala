package raft

sealed trait StateMachine[T <: Serializable, S] extends Cloneable {
  def apply(value: T): Unit
  def state(): S
  def copy(): StateMachine[T, S]
}

final class LastValue[T <: Serializable] extends StateMachine[T, T] {
  private var value: T = null.asInstanceOf[T]

  override def apply(value: T): Unit = this.value = value
  override def state(): T = this.value

  override def copy(): StateMachine[T, T] = {
    val copy = new LastValue[T]()
    copy.value = this.value
    copy
  }
}

final class DecrementCounter[T <: Serializable](init: Int = 0) extends StateMachine[T, Int] {
  private var value: Int = init

  override def apply(value: T): Unit = this.value -= 1
  override def state(): Int = this.value
  override def copy(): StateMachine[T, Int] = new DecrementCounter(this.value)
}