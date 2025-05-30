package raft.cluster 

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors.supervise

import machine.StateMachine
import raft.process.Process
import raft.cluster.{ProcessID, Cluster, GetCluster, ClusterResponse}

final class LocalCluster[T <: Serializable] {
  def apply(
    processes: Int,
    machine: StateMachine[T, T]
  ): Behavior[GetCluster[T]] = Behaviors.setup { context =>
    context.log.info("Starting {} processes", processes)
    val refsMap = (0 until processes)
      .map(i =>
        (
          ProcessID(i),
          context.spawn(
            supervise(
              Process[T]()(
                ProcessID(i),
                context.self,
                machine
              )
            )
              .onFailure[Throwable](SupervisorStrategy.restart),
            s"process-$i"
          )
        )
      )
      .toMap
    val refs = Cluster(refsMap)
    this.main(refs)
  }

  private def main(
    refs: Cluster[T],
  ): Behavior[GetCluster[T]] = Behaviors.receive { (context, message) =>
      context.log.info("{}", message)
      val GetCluster(ref) = message
      ref ! ClusterResponse(refs)
      this.main(refs)
    }
}
