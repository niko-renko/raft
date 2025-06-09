package client.ticket

import java.lang.Integer
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import cluster.{ProcessID, GetCluster, ClusterResponse}

final class TicketClientGroup {
  def apply(
      cluster: ActorRef[GetCluster[Integer]]
  ): Behavior[ClusterResponse[Integer]] = Behaviors.setup { context =>
    val workload = Map(
      ProcessID(0) -> (List.fill(5)(Action.Write) :+ Action.Read),
      ProcessID(1) -> (List.fill(5)(Action.Write) :+ Action.Read),
      ProcessID(2) -> (List(Action.Sleep) ++ List.fill(10)(Action.Read) ++ List(
        Action.Awake
      ) ++ List.fill(10)(Action.Read))
    )

    cluster ! GetCluster(context.self)
    Behaviors.receive { (context, message) =>
      val ClusterResponse(refs) = message
      refs.foreach { (id, _) =>
        context.spawn(
          TicketClient()(refs, id, workload(id)),
          s"ticket-client-${id.id}"
        )
      }
      Behaviors.receive { (context, message) => Behaviors.stopped }
    }
  }
}
