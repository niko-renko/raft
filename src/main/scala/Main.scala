import scala.io.StdIn.readLine
import akka.actor.typed.ActorSystem

import machine.LastValue
import raft.cluster.LocalCluster

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
    val system = ActorSystem(cluster, "cluster")
    // while (true) {
    //   val command = readLine()
    //   system ! Control(command)
    // }
  }
}
