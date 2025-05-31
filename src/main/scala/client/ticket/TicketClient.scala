package client.ticket

import java.io.FileWriter
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{ProcessID, Cluster, GetCluster, ClusterResponse}

private final class TicketClient[T <: Serializable] {
    def apply(
        preferred: ProcessID,
        refs: Cluster[T]
    ): Behavior[raft.client.Message | Message] = Behaviors.setup { context => 
        context.log.info("TicketClient initialized {}", preferred)
        this.main(0)
    }

    private def main(
        requestId: Int
    ): Behavior[raft.client.Message | Message] = Behaviors.receive { (context, message) =>
        context.log.trace("{}: {}", requestId, message)
        message match {
            case Count() => main(requestId + 1)
            case Book(count) => main(requestId + 1)
            case _ => Behaviors.same
        }
    }
}