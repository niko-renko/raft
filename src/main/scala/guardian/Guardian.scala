package guardian

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors.supervise

import raft.{Processes, Process, ProcessID}
import raft.{Refs, Append, Crash}

sealed trait Message

// Public
final case class GetRefs(process: ProcessID) extends Message

// Private
final private case class Start(processes: Int) extends Message
final private case class Control(command: String) extends Message

object Guardian {
  def apply(): Behavior[Message] = Behaviors.receive { (context, message) =>
    message match {
      case Start(processes) => {
        context.log.info("Starting {} processes", processes)
        val refsMap = (0 until processes)
          .map(i =>
            (
              ProcessID(i),
              context.spawn(
                supervise(Process[String]()(ProcessID(i), context.self))
                  .onFailure[Throwable](SupervisorStrategy.restart),
                s"process-$i"
              )
            )
          )
          .toMap
        val refs = Processes(refsMap)
        this.main(refs)
      }
      case _ => Behaviors.stopped
    }
  }

  private def main(
      refs: Processes[raft.Message[String]]
  ): Behavior[Message] =
    Behaviors.receive { (context, message) =>
      message match {
        case GetRefs(process) => {
          context.log.info("Received GetRefs for {}", process)
          refs.getRef(process) ! Refs(refs)
          this.main(refs)
        }
        case Control(command) if command.split(" ").size >= 2 => {
          context.log.info("Received command: {}", command)

          val parts = command.split(" ")
          val action = parts(0)
          val processId = ProcessID(parts(1).toInt)
          val process = refs.find(_._1 == processId)

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

          this.main(refs)
        }
        case _ => Behaviors.stopped
      }
    }
}
