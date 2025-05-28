package client.text

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

class TextClient[T <: Serializable] {
    def apply(translate: String => T): Behavior[raft.client.Message | Message] = Behaviors.receive { (context, message) =>
        context.log.info("{}", message)
        Behaviors.same
    }
}
