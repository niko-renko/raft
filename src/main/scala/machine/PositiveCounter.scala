package machine

import java.lang.Integer

final class PositiveCounter(init: Int = 0) extends StateMachine[Integer, Integer] {
  private var value: Int = init

  override def apply(value: Integer): Option[Unit] = {
    if (this.value + value >= 0) {
      this.value += value
      Some(())
    } else
      None
  }

  override def state(): Integer = this.value
  override def copy(): StateMachine[Integer, Integer] = new PositiveCounter(this.value)
}
