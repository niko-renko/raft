package guardian

import scala.util.Random
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors.supervise

import raft.LastValue
import raft.{ProcessID, Processes, Process}
import raft.{Crash, Sleep, Awake}
import raft.{RefsResponse, Read, ReadUnstable, Append}

sealed trait Message

// Public
final case class Refs(process: ProcessID) extends Message

// Private
final private case class Control(command: String) extends Message

object NoopClient {
  def apply(): Behavior[client.Message] = Behaviors.receive { (context, message) =>
      context.log.info("{}", message)
      this.apply()
  }
}

object Guardian {
  def apply(processes: Int): Behavior[Message] = Behaviors.setup { context =>
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
                LastValue[String]("null")
              )
            )
              .onFailure[Throwable](SupervisorStrategy.restart),
            s"process-$i"
          )
        )
      )
      .toMap
    val refs = Processes(refsMap)
    val client = context.spawn(NoopClient(), "noop-client")
    this.main(refs, client)
  }

  private def main(
      refs: Processes[String],
      clientRef: ActorRef[client.Message]
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

            case "stable"   => ref ! Read(clientRef)
            case "unstable"   => ref ! ReadUnstable(clientRef)

            case "append" => {
              val id = if (parts.size == 4)
                parts(3).toInt
              else
                Random.nextInt()

              ref ! Append(clientRef, id, parts(2))
            }

            case _        => context.log.info("Invalid command: {}", command)
          }

          this.main(refs, clientRef)
        }
        case Refs(process) => {
          refs.getRef(process) ! RefsResponse(refs)
          this.main(refs, clientRef)
        }
        case _ => Behaviors.stopped
      }
    }
}
