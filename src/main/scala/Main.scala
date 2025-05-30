import scala.io.StdIn.readLine
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import machine.LastValue
import raft.cluster.LocalCluster
import client.text.TextClient

object Guardian {
  def apply(processes: Int): Behavior[Unit] = Behaviors.setup { context =>
    val cluster = context.spawn(LocalCluster()(processes, LastValue("init")), "cluster")
    val client = context.spawn(TextClient[String]()(cluster, s => s), "text-client")
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

    val system = ActorSystem(Guardian(processes), "guardian")
  }
}
