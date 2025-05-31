package machine

trait StateMachine[T, S] extends Cloneable {
  def apply(value: T): Option[Unit]
  def state(): S
  def copy(): StateMachine[T, S]
}