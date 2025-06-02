package client.ticket

import java.lang.Integer
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import raft.cluster.{ProcessID, Cluster}

final private case class State(
    preferred: ProcessID,
    refs: Cluster[Integer],
    writeTo: ActorRef[raft.process.Message[Integer]],
    readFrom: ActorRef[raft.process.Message[Integer]],
    timers: TimerScheduler[Message | raft.client.Message],
)
