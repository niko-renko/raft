package cluster

import akka.actor.typed.ActorRef

final case class GetCluster[T <: Serializable](
    ref: ActorRef[ClusterResponse[T]]
)
final case class ClusterResponse[T <: Serializable](
    refs: Cluster[T]
)
