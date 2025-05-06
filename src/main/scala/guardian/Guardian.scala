package guardian

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors.supervise

import raft.{Processes, Process, ProcessID}
import raft.{RefsResponse, Append}
import raft.{Crash, Slow}

sealed trait Message

// Public
final case class Refs(process: ProcessID) extends Message
final case class AppendResponse(success: Boolean) extends Message

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
      refs: Processes[String]
  ): Behavior[Message] =
    Behaviors.receive { (context, message) =>
      message match {
        case Refs(process) => {
          context.log.info("Received GetRefs from {}", process)
          refs.getRef(process) ! RefsResponse(refs)
          this.main(refs)
        }
        case AppendResponse(success) => {
          context.log.info("Received AppendResponse: {}", success)
          this.main(refs)
        }
        case Control(command) if command.split(" ").size >= 2 => {
          context.log.info("Received Command: {}", command)

          val parts = command.split(" ")
          val action = parts(0)
          val processId = ProcessID(parts(1).toInt)
          val ref = refs.getRef(processId)

          action match {
            case "append" => ref ! Append(List(parts(2)))
            case "crash"  => ref ! Crash()
            case "slow"   => ref ! Slow(parts(2).toInt)
            case _        => context.log.info("Invalid command: {}", command)
          }

          this.main(refs)
        }
        case _ => Behaviors.stopped
      }
    }
}
