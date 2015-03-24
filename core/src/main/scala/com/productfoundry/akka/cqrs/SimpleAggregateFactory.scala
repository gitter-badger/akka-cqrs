package com.productfoundry.akka.cqrs

import akka.actor.{ActorRef, Props}
import com.productfoundry.akka.PassivationConfig

import scala.reflect.ClassTag

class SimpleAggregateFactory[A <: Aggregate[_, _] : ClassTag] extends AggregateFactory[A] {
  override def props(passivationConfig: PassivationConfig): Props = {
    Props(implicitly[ClassTag[A]].runtimeClass, passivationConfig)
  }
}

/**
 * Creates aggregate supervisors for aggregates without s specialized constructor.
 */
object SimpleAggregateFactory {
  def supervisor[A <: Aggregate[_, _] : ClassTag](implicit domainContext: DomainContext): ActorRef = {
    implicit lazy val aggregateFactory = new SimpleAggregateFactory[A]
    implicit lazy val aggregateSupervisorFactory = domainContext.entitySupervisorFactory[A]
    EntitySupervisor.forType[A]
  }
}