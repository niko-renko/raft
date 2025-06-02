package client.ticket

import java.lang.Integer
import scala.util.Random
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{ProcessID, Cluster}
import raft.process.{Append, ReadUnstable}
import raft.client.{AppendResponse, ReadUnstableResponse}

private object WaitKey
private object TimeoutKey

private final class TicketClient {
    def apply(
        preferred: ProcessID,
        refs: Cluster[Integer]
    ): Behavior[Message | raft.client.Message] = Behaviors.setup { context => 
        Behaviors.withTimers { timers =>
            context.log.info("TicketClient initialized {}", preferred)
            val state = State(
                preferred,
                refs,
                refs(preferred),
                timers,
            )
            this.read(context, state)
        }
    }

    private def wait(
        state: State,
        behavior: Behavior[Message | raft.client.Message]
    ): Behavior[Message | raft.client.Message] = {
        state.timers.startSingleTimer(WaitKey, Wait(), FiniteDuration(1000, TimeUnit.MILLISECONDS))

        Behaviors.receive { (context, message) =>
            message match {
                case Wait() => behavior
                case _ => Behaviors.stopped
            }
        }
    }

    private def read(
        context: ActorContext[Message | raft.client.Message],
        state: State
    ): Behavior[Message | raft.client.Message] = Behaviors.setup { context =>
        state.timers.startSingleTimer(TimeoutKey, Timeout(), FiniteDuration(1000, TimeUnit.MILLISECONDS))
        state.refs(state.preferred) ! ReadUnstable(context.self)

        Behaviors.receive { (context, message) =>
            message match {
                case Timeout() => {
                    this.wait(state, this.read(context, state))
                }
                case ReadUnstableResponse(value) => {
                    context.log.trace("Read {}", value)
                    state.timers.cancel(TimeoutKey)
                    this.wait(state, this.read(context, state))
                }
                case _ => Behaviors.stopped
            }
        }
    }
}