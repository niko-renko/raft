package client.ticket

import scala.util.Random
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{ProcessID, GetCluster, ClusterResponse}

final class TicketClientCluster[T <: Serializable] {
    def apply(
        cluster: ActorRef[GetCluster[T]],
    ): Behavior[ClusterResponse[T]] = Behaviors.setup { context =>
        cluster ! GetCluster(context.self)
        Behaviors.receive { (context, message) => 
            val ClusterResponse(refs) = message
            val clients = refs.zipWithIndex.map { (ref, index) => 
                context.spawn(TicketClient()(ProcessID(index), refs), s"ticket-client-$index")
            }
            clients.foreach(
                ref => (1 to 100).map(_ => ref ! (if (Random.nextBoolean()) Count() else Book(1)))
            )
            Behaviors.receive { (context, message) => Behaviors.stopped }
        }
    }
}