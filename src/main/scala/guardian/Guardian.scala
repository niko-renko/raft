package guardian

import scala.util.Random
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors.supervise

import raft.{LastValue, Processes, Process, ProcessID}
import raft.{RefsResponse, Read, Append}
import raft.{Crash, Sleep, Awake}

sealed trait Message

// Public
final case class Refs(process: ProcessID) extends Message
final case class AppendResponse(
    id: Int,
    success: Boolean,
    leaderId: Option[ProcessID]
) extends Message
final case class ReadResponse(
    value: String
) extends Message

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
                supervise(
                  Process[String]()(
                    ProcessID(i),
                    context.self,
                    LastValue[String]()
                  )
                )
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
      context.log.info("{}", message)
      message match {
        case Control(command) if command.split(" ").size >= 2 => {
          val parts = command.split(" ")
          val action = parts(0)
          val processId = ProcessID(parts(1).toInt)
          val ref = refs.getRef(processId)

          action match {
            case "crash"  => ref ! Crash()
            case "sleep"  => ref ! Sleep(java.lang.Boolean.parseBoolean(parts(2)))
            case "awake"  => ref ! Awake()

            case "read"   => ref ! Read()
            case "append" => ref ! Append(if (parts.size == 4) parts(3).toInt else Random.nextInt(), parts(2))
            case _        => context.log.info("Invalid command: {}", command)
          }

          this.main(refs)
        }
        case Refs(process) => {
          refs.getRef(process) ! RefsResponse(refs)
          this.main(refs)
        }
        case ReadResponse(_) => this.main(refs)
        case AppendResponse(_, _, _) => this.main(refs)
        case _                       => Behaviors.stopped
      }
    }
}
