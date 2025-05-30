package machine

final class LastValue[T](init: T) extends StateMachine[T, T] {
  private var value: T = init

  override def apply(value: T): Unit = this.value = value
  override def state(): T = this.value
  override def copy(): StateMachine[T, T] = new LastValue(this.value)
}

