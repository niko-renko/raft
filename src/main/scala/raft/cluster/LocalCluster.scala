package raft.cluster 

import scala.util.Random
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors.supervise

import machine.StateMachine
import raft.process.{ProcessID, ProcessRegistry, Process}
import raft.process.{RefsResponse, Crash, Sleep, Awake, Read, ReadUnstable, Append}

private object NoopClient {
  def apply(): Behavior[raft.client.Message] = Behaviors.receive { (context, message) =>
      context.log.info("{}", message)
      this.apply()
  }
}

final class LocalCluster[T <: Serializable] {
  def apply(processes: Int, machine: StateMachine[T, T]): Behavior[Message] = Behaviors.setup { context =>
    context.log.info("Starting {} processes", processes)
    val refsMap = (0 until processes)
      .map(i =>
        (
          ProcessID(i),
          context.spawn(
            supervise(
              Process[T]()(
                ProcessID(i),
                context.self,
                machine
              )
            )
              .onFailure[Throwable](SupervisorStrategy.restart),
            s"process-$i"
          )
        )
      )
      .toMap
    // TODO: rename
    val refs = ProcessRegistry(refsMap)
    val client = context.spawn(NoopClient(), "noop-client")
    this.main(refs, client)
  }

  private def main(
      refs: ProcessRegistry[T],
      clientRef: ActorRef[raft.client.Message]
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

              // ref ! Append(clientRef, id, parts(2))
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
