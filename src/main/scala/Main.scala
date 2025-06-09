import java.lang.Integer
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.Cluster

import machine.{LastValue, PositiveCounter}
import cluster.local.LocalCluster
import client.text.TextClient
import client.ticket.TicketClientCluster

object LastValueSystem {
  def apply(processes: Int): Behavior[Cluster] = Behaviors.setup { context =>
    val cluster = context.spawn(
      LocalCluster[String]()(processes, LastValue("init")),
      "cluster"
    )
    context.spawn(TextClient[String]()(cluster, s => s), "text-client")
    Behaviors.receive { (context, message) => Behaviors.same }
  }
}

object LocalTicketSystem {
  def apply(processes: Int): Behavior[Cluster] = Behaviors.setup { context =>
    val cluster = context.spawn(
      LocalCluster[Integer]()(processes, PositiveCounter(10)),
      "cluster"
    )
    context.spawn(TextClient[Integer]()(cluster, s => s.toInt), "text-client")
    context.spawn(TicketClientCluster()(cluster), "ticket-client-cluster")
    Behaviors.receive { (context, message) => Behaviors.same }
  }
}

object RemoteTicketSystem {
  def apply(processes: Int): Behavior[Cluster] = Behaviors.setup { context =>
    Behaviors.receive { (context, message) =>
      println(message.state)
      Behaviors.same
    }
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    if (args.size != 1) {
      println("Usage: Main processes")
      sys.exit(1)
    }

    val processes = args(0).toInt
    if (processes <= 0) {
      println("Process number must be greater than 0")
      sys.exit(1)
    }

    // ActorSystem(LastValueSystem(processes), "system")
    val system = ActorSystem(RemoteTicketSystem(processes), "system")
    val cluster = Cluster(system)
    system ! cluster
  }
}
