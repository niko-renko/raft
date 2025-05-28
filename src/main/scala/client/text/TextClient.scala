package client.text

import scala.io.StdIn.readLine
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import raft.cluster.{Cluster, GetCluster, ClusterResponse}

class TextClient[T <: Serializable] {
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
                    this.main(refs)
                }
                case _ => Behaviors.stopped
            } 
        }
    }

    private def main(
        refs: Cluster[T]
    ): Behavior[ClusterResponse[T] | raft.client.Message | Message] = Behaviors.receive { (context, message) =>
        message match {
            case Control(command) => {
                println(command)
                this.main(refs)
            }
            case message: raft.client.Message => this.main(refs)
            case _ => Behaviors.stopped
        }
    }
}
