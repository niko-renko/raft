package machine

import java.lang.Integer

final class DecrementCounter(init: Int = 0) extends StateMachine[Integer, Int] {
  private var value: Int = init

  override def apply(value: Integer): Unit = this.value -= value
  override def state(): Int = this.value
  override def copy(): StateMachine[Integer, Int] = new DecrementCounter(this.value)
}
