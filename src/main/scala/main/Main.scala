package main

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import raft.{Processes, Process, ProcessID, Refs, Append}

object Guardian {
  final case class Start(processes: Int)

  def apply(): Behavior[Start] = Behaviors.receive { (context, message) =>
    context.log.info("Starting {} processes", message.processes)
    val refsMap = (0 until message.processes)
      .map(i =>
        (ProcessID(i), context.spawn(Process[String]()(), s"process-$i"))
      )
      .toMap
    val refs = Processes(refsMap)
    refs.foreach((id, ref) => ref ! Refs(id, refs))
    refs.foreach((id, ref) => ref ! Append(List("Hello, world!")))
    Behaviors.ignore
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

    val system = ActorSystem(Guardian(), "guardian")
    system ! Guardian.Start(processes)
  }
}
