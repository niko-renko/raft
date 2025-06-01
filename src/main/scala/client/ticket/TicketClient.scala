package client.ticket

import scala.util.Random
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{ProcessID, Cluster, GetCluster, ClusterResponse}
import raft.process.{Append, ReadUnstable}

private final class TicketClient[T <: Serializable] {
    def apply(
        preferred: ProcessID,
        refs: Cluster[T]
    ): Behavior[raft.client.Message | Message] = Behaviors.setup { context => 
        context.log.info("TicketClient initialized {}", preferred)
        this.main(preferred, refs, 0)
    }

    private def main(
        preferred: ProcessID,
        refs: Cluster[T],
        requestId: Int
    ): Behavior[raft.client.Message | Message] = Behaviors.receive { (context, message) =>
        context.log.trace("{}: {}", requestId, message)
        message match {
            case Count() => {
                refs(preferred) ! ReadUnstable(context.self)
                main(preferred, refs, requestId + 1)
            }
            case Book(count) => {
                refs(preferred) ! Append(context.self, Random.nextInt(), count)
                main(preferred, refs, requestId + 1)
            }
            case _ => Behaviors.stopped
        }
    }
}