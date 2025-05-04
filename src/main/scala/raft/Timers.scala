package raft

import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import java.util.concurrent.TimeUnit
import akka.actor.typed.scaladsl.TimerScheduler

private sealed trait TimerKey

private object Election extends TimerKey
private object Heartbeat extends TimerKey
private object Pause extends TimerKey

final private class Timers[T <: Serializable](
    timers: TimerScheduler[Message[T]]
) {
  private var registry: Map[TimerKey, (Message[T], Int, Int)] = Map()

  def set(key: TimerKey) = {
    timers.cancelAll()
    val (message, from, to) = this.registry(key)
    timers.startSingleTimer(
      key,
      message,
      FiniteDuration(from + Random.nextInt(to - from), TimeUnit.MILLISECONDS)
    )
  }

  def set(key: TimerKey, ms: Int) = {
    timers.cancelAll()
    val (message, _, _) = this.registry(key)
    timers.startSingleTimer(
      key,
      message,
      FiniteDuration(ms, TimeUnit.MILLISECONDS)
    )
  }

  def register(key: TimerKey, message: Message[T], range: (Int, Int)) = {
    this.registry = this.registry + (key -> (message, range._1, range._2))
  }
}
