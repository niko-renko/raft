package client.ticket

import java.lang.Integer
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import raft.cluster.{ProcessID, Cluster}

private enum Action:
    case Sleep
    case Awake
    case Read
    case Write

final private case class State(
    refs: Cluster[Integer],
    workload: List[Action],
    step: Int,
    preferred: ProcessID,
    leaderId: ProcessID,
    writeTo: ActorRef[raft.process.Message[Integer]],
    readFrom: ActorRef[raft.process.Message[Integer]],
    timers: TimerScheduler[Message | raft.client.Message],
)
