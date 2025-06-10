package cluster.remote

import scala.util.Random
import akka.cluster.typed.Cluster
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey

import machine.StateMachine
import cluster.{ProcessID, GetCluster, ClusterResponse}
import raft.Process

final private case class Hello[T <: Serializable](
    ref: ActorRef[HelloResponse[T]]
)
final private case class HelloResponse[T <: Serializable](
    id: ProcessID,
    ref: ActorRef[raft.Message[T]]
)

private object Hello {
  def apply[T <: Serializable](
      id: ProcessID,
      process: ActorRef[raft.Message[T]]
  ): Behavior[Hello[T]] = Behaviors.receive { (context, message) =>
    println("Hello")
    val Hello(ref) = message
    ref ! HelloResponse(id, process)
    Behaviors.same
  }
}

final class RemoteCluster[T <: Serializable] {
  def apply(
      processes: Int,
      cluster: Cluster,
      machine: StateMachine[T, T]
  ): Behavior[GetCluster[T] | Receptionist.Listing | HelloResponse[T]] =
    Behaviors.setup { context =>
      val id = ProcessID(Random.nextInt())
      val process =
        context.spawn(Process[T]()(id, context.self, machine), "raft")
      val map = Map(id -> process)

      val hello = context.spawn(Hello[T](id, process), "hello")
      val key = ServiceKey[Hello[T]]("hello")
      context.system.receptionist ! Receptionist.Register(key, hello)
      context.system.receptionist ! Receptionist.Subscribe(key, context.self)

      this.main(processes, id, map, List())
    }

  private def main(
      processes: Int,
      id: ProcessID,
      map: Map[ProcessID, ActorRef[raft.Message[T]]],
      pending: List[ActorRef[ClusterResponse[T]]]
  ): Behavior[GetCluster[T] | Receptionist.Listing | HelloResponse[T]] =
    Behaviors.receive { (context, message) =>
      message match {
        case GetCluster(ref) => {
          val npending =
            if (map.size != processes)
              pending :+ ref
            else {
              ref ! ClusterResponse(cluster.Cluster(map))
              pending
            }

          this.main(processes, id, map, npending)
        }
        case listing: Receptionist.Listing => {
          val key = ServiceKey[Hello[T]]("hello")
          val refs = listing.serviceInstances(key)

          if (refs.size == processes)
            refs.foreach(ref => ref ! Hello(context.self))

          Behaviors.same
        }
        case HelloResponse(id, ref) => {
          val nmap = map + (id -> ref)

          val npending =
            if (nmap.size == processes) {
              pending.foreach(ref =>
                ref ! ClusterResponse(cluster.Cluster(nmap))
              )
              List()
            } else
              pending

          this.main(processes, id, nmap, npending)
        }
      }
    }
}
