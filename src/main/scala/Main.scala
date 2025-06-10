import java.lang.Integer
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.Cluster
import com.typesafe.config.ConfigFactory

import machine.PositiveCounter
import cluster.local.LocalCluster
import cluster.remote.RemoteCluster
import client.text.TextClient
import client.ticket.TicketClientGroup

object LocalTicketSystem {
  def apply(processes: Int): Behavior[Cluster] = Behaviors.setup { context =>
    val cluster = context.spawn(
      LocalCluster[Integer]()(processes, PositiveCounter(10)),
      "cluster"
    )
    context.spawn(TextClient[Integer]()(cluster, s => s.toInt), "text-client")
    context.spawn(TicketClientGroup()(cluster), "ticket-client-group")
    Behaviors.ignore
  }
}

object RemoteTicketSystem {
  def apply(processes: Int): Behavior[Cluster] = Behaviors.receive {
    (context, message) =>
      context.spawn(
        RemoteCluster[Integer]()(processes, message, PositiveCounter(10)),
        "cluster"
      )
      Behaviors.ignore
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    System.setProperty("timestamp", System.currentTimeMillis().toString)

    val processes = sys.env.getOrElse("COUNT", "3").toInt
    if (processes <= 1) {
      println("Process number must be greater than 1")
      sys.exit(1)
    }
    if (processes % 2 == 0) {
      println("Process number must be odd")
      sys.exit(1)
    }
    val behavior = sys.env.getOrElse("SYSTEM", "local") match {
      case "local"  => LocalTicketSystem(processes)
      case "remote" => RemoteTicketSystem(processes)
      case _        => throw new Exception("Unreachable")
    }
    val hostname = sys.env.getOrElse("HOSTNAME", "localhost")
    val port = sys.env.getOrElse("PORT", "2551").toInt
    val seedHostname = sys.env.getOrElse("SEED_HOSTNAME", hostname)
    val seedPort = sys.env.getOrElse("SEED_PORT", port.toString).toInt

    val config = ConfigFactory.parseString(s"""
      akka {
        actor {
          provider = cluster
          default-dispatcher {
            type = Dispatcher
            executor = "thread-pool-executor"
            thread-pool-executor {
              fixed-pool-size = 16
              maximum-cores-per-factor = 1
              task-queue-size = 512
            }
            throughput = 1
          }
          allow-java-serialization = on
        }
        remote.artery {
          canonical.hostname = "$hostname"
          canonical.port = $port
        }
        cluster {
          seed-nodes = [
            "akka://system@$seedHostname:$seedPort"
          ]
        }
        log-dead-letters = 0
        log-dead-letters-during-shutdown = off
      }
    """)

    val system = ActorSystem(behavior, "system", config)
    val cluster = Cluster(system)
    system ! cluster
  }
}
