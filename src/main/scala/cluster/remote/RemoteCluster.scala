package cluster.remote

import machine.StateMachine
import akka.cluster.typed.Cluster
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

final class RemoteCluster[T <: Serializable] {
  def apply(
      processes: Int,
      cluster: Cluster,
      machine: StateMachine[T, T]
  ): Behavior[Cluster] = Behaviors.setup { context =>
    println(cluster.state.members.size)
    Behaviors.ignore
  }
}
