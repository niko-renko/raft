import scala.io.StdIn.readLine
import java.lang.Integer
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import machine.{LastValue, DecrementCounter}
import raft.cluster.LocalCluster
import client.text.TextClient
import client.ticket.TicketClientCluster

object LastValueSystem {
  def apply(processes: Int): Behavior[Unit] = Behaviors.setup { context =>
    val cluster = context.spawn(LocalCluster[String]()(processes, LastValue("init")), "cluster")
    context.spawn(TextClient[String]()(cluster, s => s), "text-client")
    Behaviors.receive { (context, message) => Behaviors.same }
  }
}

object TicketSystem {
  def apply(processes: Int): Behavior[Unit] = Behaviors.setup { context =>
    val cluster = context.spawn(LocalCluster[Integer]()(processes, DecrementCounter(10)), "cluster")
    context.spawn(TextClient[Integer]()(cluster, s => s.toInt), "text-client")
    context.spawn(TicketClientCluster[Integer]()(cluster), "ticket-client-cluster")
    Behaviors.receive { (context, message) => Behaviors.same }
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

    val system = ActorSystem(TicketSystem(processes), "system")
  }
}
