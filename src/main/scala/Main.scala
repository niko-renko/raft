import scala.io.StdIn.readLine
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import machine.LastValue
import raft.cluster.LocalCluster
import client.text.TextClient

object Guardian {
  def apply(actors: List[(String, Behavior[?])]): Behavior[Unit] = Behaviors.setup { context =>
    actors.map{ case (name, actor) => context.spawn(actor, name) }
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

    val cluster = LocalCluster()(processes, LastValue("init"))
    val client = TextClient[String]()(cluster, s => s)
    val guardian = Guardian(List(("cluster", cluster), ("text-client", client)))
    val system = ActorSystem(guardian, "guardian")
  }
}
