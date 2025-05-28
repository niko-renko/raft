package raft.process

import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import java.util.concurrent.TimeUnit
import akka.actor.typed.scaladsl.TimerScheduler

import raft.cluster.ClusterResponse

private sealed trait TimerKey

final private class Timer[T <: Serializable](
    timers: TimerScheduler[ClusterResponse[T] | Message[T]]
) {
  object Election extends TimerKey
  object Heartbeat extends TimerKey
  object Sleep extends TimerKey

  private var registry: Map[TimerKey, (Message[T], Int, Int)] = Map()

  def set(key: TimerKey) = {
    val (message, from, to) = this.registry(key)
    timers.cancelAll()
    timers.startSingleTimer(
      key,
      message,
      FiniteDuration(from + Random.nextInt(to - from), TimeUnit.MILLISECONDS)
    )
  }

  def set(key: TimerKey, ms: Int) = {
    val (message, _, _) = this.registry(key)
    timers.cancelAll()
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
