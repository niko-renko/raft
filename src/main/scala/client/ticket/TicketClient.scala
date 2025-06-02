package client.ticket

import java.lang.Integer
import scala.util.Random
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{ProcessID, Cluster}
import raft.process.{Append, ReadUnstable}
import raft.client.{AppendResponse, ReadUnstableResponse}

private final class TicketClient {
    def apply(
        preferred: ProcessID,
        refs: Cluster[Integer]
    ): Behavior[raft.client.Message] = Behaviors.setup { context => 
        Behaviors.withTimers { timers =>
            context.log.info("TicketClient initialized {}", preferred)
            val state = State(
                preferred,
                refs,
                0,
                refs(preferred),
                timers
            )
            this.main(state)
        }
    }

    private def main(
        state: State
    ): Behavior[raft.client.Message] = Behaviors.receive { (context, message) =>
        context.log.trace("{}: {}", state.requestId, message)
        Behaviors.same
        // message match {
        //     case Count() => {
        //         refs(preferred) ! ReadUnstable(context.self)
        //         Behaviors.receive { (context, message) =>
        //             message match {
        //                 case ReadUnstableResponse(value) => {
        //                     context.log.trace("{}: {}", requestId, value)
        //                     main(preferred, refs, requestId + 1)
        //                 }
        //             }
        //         }
        //         // main(preferred, refs, requestId + 1)
        //     } case Book(count) => {
        //         refs(preferred) ! Append(context.self, Random.nextInt(), count)
        //         Behaviors.receive { (context, message) =>
        //             message match {
        //                 case AppendResponse(id, success, leaderId) => {
        //                     context.log.trace("{}: {}", requestId, message)
        //                     main(preferred, refs, requestId + 1)
        //                 }
        //             }
        //         }
        //         // main(preferred, refs, requestId + 1)
        //     }
        //     case _ => Behaviors.stopped
        // }
    }
}