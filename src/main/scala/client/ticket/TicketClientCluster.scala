package client.ticket

import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{ProcessID, GetCluster, ClusterResponse}

final class TicketClientCluster[T <: Serializable] {
    def apply(
        cluster: ActorRef[GetCluster[T]],
    ): Behavior[ClusterResponse[T] | raft.client.Message] = Behaviors.setup { context =>
        cluster ! GetCluster(context.self)
        Behaviors.receive { (context, message) => 
            message match {
                case ClusterResponse(refs) => Behaviors.setup { context => 
                    refs.zipWithIndex.foreach { (ref, index) => 
                        context.spawn(TicketClient()(ProcessID(index), refs), s"ticket-client-$index")
                    }
                    Behaviors.receive { (context, message) => Behaviors.stopped }
                }
                case _ => Behaviors.stopped
            } 
        }
    }
}