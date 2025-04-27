package main

import scala.io.StdIn.readLine
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors.supervise

import raft.{Processes, Process, ProcessID}
import raft.{Message, Refs, Append, Crash}

object Guardian {
  sealed trait Command
  final case class Start(processes: Int) extends Command
  final case class Control(command: String) extends Command

  def apply(): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case Start(processes) => {
        context.log.info("Starting {} processes", processes)
        val refsMap = (0 until processes)
          .map(i =>
            (
              ProcessID(i),
              context.spawn(
                supervise(Process[String]()())
                  .onFailure[Throwable](SupervisorStrategy.restart),
                s"process-$i"
              )
            )
          )
          .toMap
        val refs = Processes(refsMap)
        refs.foreach((id, ref) => ref ! Refs(id, refs))
        this.control(refs)
      }
      case _ => Behaviors.stopped
    }
  }

  def control(processes: Processes[Message[String]]): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case Control(command) if command.split(" ").size >= 2 => {
          context.log.info("Received command: {}", command)

          val parts = command.split(" ")
          val action = parts(0)
          val processId = ProcessID(parts(1).toInt)
          val process = processes.find(_._1 == processId)

          if (!process.isEmpty) {
            val ref = process.get._2
            action match {
              case "crash"  => ref ! Crash()
              case "append" => ref ! Append(List(parts(2)))
              case _        => context.log.info("Invalid command: {}", command)
            }
          } else {
            context.log.info("Process {} not found", processId)
          }

          Behaviors.same
        }
        case _ => Behaviors.stopped
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

    val system = ActorSystem(Guardian(), "guardian")
    system ! Guardian.Start(processes)
    while (true) {
      val command = readLine()
      system ! Guardian.Control(command)
    }
  }
}
