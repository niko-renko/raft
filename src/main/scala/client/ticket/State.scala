package client.ticket

import java.lang.Integer
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import raft.cluster.{ProcessID, Cluster}

final private case class State(
    preferred: ProcessID,
    refs: Cluster[Integer],
    requestId: Int,
    leader: ActorRef[raft.process.Message[Integer]],
    timers: TimerScheduler[raft.client.Message],
)
