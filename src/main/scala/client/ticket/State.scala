package client.ticket

import java.lang.Integer
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import raft.cluster.{ProcessID, Cluster}

final private case class State(
    refs: Cluster[Integer],
    preferred: ProcessID,
    leaderId: ProcessID,
    writeTo: ActorRef[raft.process.Message[Integer]],
    readFrom: ActorRef[raft.process.Message[Integer]],
    timers: TimerScheduler[Message | raft.client.Message],
)
