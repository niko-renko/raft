package client.ticket

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{ProcessID, Cluster, GetCluster, ClusterResponse}

private final class TicketClient[T <: Serializable] {
    def apply(
        preferred: ProcessID,
        refs: Cluster[T]
    ): Behavior[ClusterResponse[T] | raft.client.Message] = Behaviors.setup { context => 
        context.log.info("TicketClient initialized {}", preferred)
        Behaviors.receive { (context, message) => Behaviors.same }
    }

    private def main(
    ): Behavior[ClusterResponse[T] | raft.client.Message] = Behaviors.receive { (context, message) => Behaviors.same }
}