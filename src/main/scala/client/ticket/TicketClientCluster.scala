package client.ticket

import java.lang.Integer
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{GetCluster, ClusterResponse}

final class TicketClientCluster {
    def apply(
        cluster: ActorRef[GetCluster[Integer]],
    ): Behavior[ClusterResponse[Integer]] = Behaviors.setup { context =>
        cluster ! GetCluster(context.self)
        Behaviors.receive { (context, message) => 
            val ClusterResponse(refs) = message
            refs.foreach { (id, _) => 
                context.spawn(TicketClient()(refs, id), s"ticket-client-${id.id}")
            }
            Behaviors.receive { (context, message) => Behaviors.stopped }
        }
    }
}