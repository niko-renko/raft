package client.ticket

import java.lang.Integer
import scala.util.Random
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{ProcessID, Cluster}
import raft.process.{Sleep, Awake, Append, ReadUnstable}
import raft.client.{AppendResponse, ReadUnstableResponse}

private object WaitKey
private object TimeoutKey

private final class TicketClient {
  def apply(
      refs: Cluster[Integer],
      preferred: ProcessID,
      workload: List[Action]
  ): Behavior[Message | raft.client.Message] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      context.log.info("TicketClient initialized {}", preferred)
      val state = State(
        refs,
        workload,
        0,
        preferred,
        preferred,
        refs(preferred),
        refs(preferred),
        timers
      )
      this.exec(state, 0)
    }
  }

  private def wait(
      state: State,
      behavior: Behavior[Message | raft.client.Message]
  ): Behavior[Message | raft.client.Message] = {
    state.timers.startSingleTimer(
      WaitKey,
      Wait(),
      FiniteDuration(1000, TimeUnit.MILLISECONDS)
    )

    Behaviors.receive { (context, message) =>
      message match {
        case Wait() => behavior
        case _      => Behaviors.same
      }
    }
  }

  private def exec(
      state: State,
      advance: Int
  ): Behavior[Message | raft.client.Message] = {
    val nstate = state.copy(
      step = state.step + advance
    )
    if (nstate.step >= nstate.workload.size)
      Behaviors.stopped
    else
      nstate.workload(nstate.step) match {
        case Action.Sleep => {
          state.refs(state.preferred) ! Sleep(true)
          this.exec(nstate, 1)
        }
        case Action.Awake => {
          state.refs(state.preferred) ! Awake()
          this.exec(nstate, 1)
        }
        case Action.Read  => this.wait(nstate, this.read(nstate))
        case Action.Write => this.wait(nstate, this.write(nstate))
      }
  }

  private def read(
      state: State
  ): Behavior[Message | raft.client.Message] = Behaviors.setup { context =>
    state.timers.startSingleTimer(
      TimeoutKey,
      Timeout(),
      FiniteDuration(100, TimeUnit.MILLISECONDS)
    )
    state.readFrom ! ReadUnstable(context.self)

    Behaviors.receive { (context, message) =>
      message match {
        case Timeout() => {
          context.log.trace("Read timeout from {}", state.readFrom)
          val peers = state.refs.peers(state.preferred).toList
          val nstate = state.copy(
            readFrom = peers(Random.nextInt(peers.size))._2
          )
          this.exec(nstate, 0)
        }
        case ReadUnstableResponse(value) => {
          context.log.trace("Read {} from {}", value, state.readFrom)
          state.timers.cancel(TimeoutKey)
          val nstate = state.copy(
            readFrom = state.refs(state.preferred)
          )
          this.exec(nstate, 1)
        }
        case AppendResponse(_, _, _) => Behaviors.same
        case _                       => Behaviors.stopped
      }
    }
  }

  private def write(
      state: State
  ): Behavior[Message | raft.client.Message] = Behaviors.setup { context =>
    val id = Random.nextInt()
    state.timers.startSingleTimer(
      TimeoutKey,
      Timeout(),
      FiniteDuration(100, TimeUnit.MILLISECONDS)
    )
    state.writeTo ! Append(context.self, id, -1)

    Behaviors.receive { (context, message) =>
      message match {
        case Timeout() => {
          context.log.trace("Write timeout from {}", state.writeTo)
          val peers = state.refs.peers(state.leaderId).toList
          val nstate = state.copy(
            writeTo = peers(Random.nextInt(peers.size))._2
          )
          this.exec(nstate, 0)
        }
        case ReadUnstableResponse(_) => Behaviors.same
        case AppendResponse(incomingId, _, _) if incomingId != id =>
          Behaviors.same
        case AppendResponse(_, _, leaderId) if !leaderId.isDefined => {
          state.timers.cancel(TimeoutKey)
          context.log.trace("No leader")
          this.exec(state, 0)
        }
        case AppendResponse(_, success, leaderId) => {
          state.timers.cancel(TimeoutKey)
          val nstate = state.copy(
            leaderId = leaderId.get,
            writeTo = state.refs(leaderId.get)
          )
          if (state.writeTo != nstate.writeTo) {
            context.log.trace("Leader set to {}", leaderId)
            this.exec(nstate, 0)
          } else {
            context.log.trace("Appended {} to {}", success, state.writeTo)
            this.exec(nstate, 1)
          }
        }
        case _ => Behaviors.stopped
      }
    }
  }
}
