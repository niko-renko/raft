package machine

import java.lang.Integer

final class DecrementCounter(init: Int = 0) extends StateMachine[Integer, Integer] {
  private var value: Int = init

  override def apply(value: Integer): Unit = this.value -= value
  override def state(): Integer = this.value
  override def copy(): StateMachine[Integer, Integer] = new DecrementCounter(this.value)
}
