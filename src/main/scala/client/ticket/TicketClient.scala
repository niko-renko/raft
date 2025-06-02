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
        preferred: ProcessID
    ): Behavior[Message | raft.client.Message] = Behaviors.setup { context => 
        Behaviors.withTimers { timers =>
            context.log.info("TicketClient initialized {}", preferred)
            val state = State(
                refs,
                preferred,
                preferred,
                refs(preferred),
                refs(preferred),
                timers,
            )
            this.read(state)
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
                case _ => Behaviors.same
            }
        }
    }

    private def read(
        state: State
    ): Behavior[Message | raft.client.Message] = Behaviors.setup { context =>
        if (Random.nextInt(10) == 0)
            state.refs(state.preferred) ! Sleep(false)
        if (Random.nextInt(10) == 0)
            state.refs(state.preferred) ! Awake()

        state.timers.startSingleTimer(TimeoutKey, Timeout(), FiniteDuration(100, TimeUnit.MILLISECONDS))
        state.readFrom ! ReadUnstable(context.self)

        Behaviors.receive { (context, message) =>
            message match {
                case Timeout() => {
                    context.log.trace("Read timeout from {}", state.readFrom)
                    val peers = state.refs.peers(state.preferred).toList
                    val nstate = state.copy(
                        readFrom = peers(Random.nextInt(peers.size))._2
                    )
                    this.wait(nstate, this.read(nstate))
                }
                case ReadUnstableResponse(value) => {
                    context.log.trace("Read {} from {}", value, state.readFrom)
                    state.timers.cancel(TimeoutKey)
                    val nstate = state.copy(
                        readFrom = state.refs(state.preferred)
                    )
                    this.wait(nstate, this.write(nstate))
                }
                case AppendResponse(_, _, _) => Behaviors.same
                case _ => Behaviors.stopped
            }
        }
    }

    private def write(
        state: State
    ): Behavior[Message | raft.client.Message] = Behaviors.setup { context =>
        if (Random.nextInt(10) == 0)
            state.refs(state.preferred) ! Sleep(false)
        if (Random.nextInt(10) == 0)
            state.refs(state.preferred) ! Awake()

        val id = Random.nextInt()
        state.timers.startSingleTimer(TimeoutKey, Timeout(), FiniteDuration(100, TimeUnit.MILLISECONDS))
        state.writeTo ! Append(context.self, id, -1)

        Behaviors.receive { (context, message) =>
            message match {
                case Timeout() => {
                    context.log.trace("Write timeout from {}", state.writeTo)
                    val peers = state.refs.peers(state.leaderId).toList
                    val nstate = state.copy(
                        writeTo = peers(Random.nextInt(peers.size))._2
                    )
                    this.wait(nstate, this.write(nstate))
                }
                case ReadUnstableResponse(_) => Behaviors.same
                case AppendResponse(incomingId, _, _) if incomingId != id => Behaviors.same
                case AppendResponse(_, _, leaderId) if !leaderId.isDefined => {
                    state.timers.cancel(TimeoutKey)
                    context.log.trace("No leader")
                    this.wait(state, this.write(state))
                }
                case AppendResponse(_, success, leaderId) => {
                    state.timers.cancel(TimeoutKey)
                    val nstate = state.copy(
                        leaderId = leaderId.get,
                        writeTo = state.refs(leaderId.get)
                    )
                    if (state.writeTo != nstate.writeTo) {
                        context.log.trace("Leader set to {}", leaderId)
                        this.wait(nstate, this.write(nstate))
                    } else {
                        context.log.trace("Appended {} to {}", success, state.writeTo)
                        this.wait(nstate, this.read(nstate))
                    }
                }
                case _ => Behaviors.stopped
            }
        }
    }
}