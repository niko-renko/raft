package machine

trait StateMachine[T <: Serializable, S] extends Cloneable {
  def apply(value: T): Unit
  def state(): S
  def copy(): StateMachine[T, S]
}