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
import raft.process.{Sleep, Awake, Append, ReadUnstable}
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
        if (Random.nextInt(10) == 0)
            state.refs(state.preferred) ! Sleep(false, false)
        if (Random.nextInt(10) == 0)
            state.refs(state.preferred) ! Awake()

        state.timers.startSingleTimer(TimeoutKey, Timeout(), FiniteDuration(1000, TimeUnit.MILLISECONDS))
        state.readFrom ! ReadUnstable(context.self)

        Behaviors.receive { (context, message) =>
            message match {
                case Timeout() => {
                    context.log.trace("Timeout from {}", state.readFrom)
                    val peers = state.refs.peers(state.preferred).toList
                    val nstate = state.copy(
                        readFrom = peers(Random.nextInt(peers.size))._2
                    )
                    this.wait(nstate, this.read(context, nstate))
                }
                case ReadUnstableResponse(value) => {
                    context.log.trace("Read {} from {}", value, state.readFrom)
                    state.timers.cancel(TimeoutKey)
                    val nstate = state.copy(
                        readFrom = state.refs(state.preferred)
                    )
                    this.wait(nstate, this.read(context, nstate))
                }
                case _ => Behaviors.stopped
            }
        }
    }
}