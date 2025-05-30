package client.text

import scala.util.Random
import scala.io.StdIn.readLine
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.process.{Crash, Sleep, Awake, Read, ReadUnstable, Append}
import raft.cluster.{ProcessID, Cluster, GetCluster, ClusterResponse}

final class TextClient[T <: Serializable] {
    def apply(
        cluster: ActorRef[GetCluster[T]],
        translate: String => T
    ): Behavior[ClusterResponse[T] | raft.client.Message | Message] = Behaviors.setup { context =>
        cluster ! GetCluster(context.self)
        Behaviors.receive { (context, message) => 
            message match {
                case ClusterResponse(refs) => Behaviors.setup { context => 
                    val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))
                    val future = Future {
                        while (true) {
                          val command = readLine()
                          context.self ! Control(command)
                        }
                    }(ec)
                    this.main(refs, translate)
                }
                case _ => Behaviors.stopped
            } 
        }
    }

    private def main(
        refs: Cluster[T],
        translate: String => T
    ): Behavior[ClusterResponse[T] | raft.client.Message | Message] = Behaviors.receive { (context, message) =>
        context.log.info("{}", message)
        message match {
            case Control(command) if command.split(" ").size >= 2 => {
              val parts = command.split(" ")
              val action = parts(0)
              val processId = ProcessID(parts(1).toInt)
              val ref = refs(processId)

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

                  ref ! Append(context.self, id, translate(parts(2)))
                }

                case _        => context.log.info("Invalid command: {}", command)
              }

              this.main(refs, translate)
            }
            case message: raft.client.Message => this.main(refs, translate)
            case _ => Behaviors.stopped
        }
    }
}
