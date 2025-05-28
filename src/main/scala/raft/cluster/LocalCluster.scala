package raft.cluster 

import scala.util.Random
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors.supervise

import machine.StateMachine
import raft.process.{ProcessID, ProcessRegistry, Process}
import raft.process.{RefsResponse, Crash, Sleep, Awake, Read, ReadUnstable, Append}

final class LocalCluster[T <: Serializable] {
  def apply(processes: Int, machine: StateMachine[T, T]): Behavior[Message | raft.client.Message] = Behaviors.setup { context =>
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
    val refs = ProcessRegistry(refsMap)
    this.main(refs)
  }

  private def main(refs: ProcessRegistry[T]): Behavior[Message | raft.client.Message] =
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

            case "stable"   => ref ! Read(context.self)
            case "unstable"   => ref ! ReadUnstable(context.self)

            case "append" => {
              val id = if (parts.size == 4)
                parts(3).toInt
              else
                Random.nextInt()

              // ref ! Append(clientRef, id, parts(2))
            }

            case _        => context.log.info("Invalid command: {}", command)
          }

          this.main(refs)
        }
        case Refs(ref) => {
          ref ! RefsResponse(refs)
          this.main(refs)
        }
        case message: raft.client.Message => this.main(refs)
        case _ => Behaviors.stopped
      }
    }
}
