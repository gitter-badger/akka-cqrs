package com.productfoundry.akka.cqrs.process

import akka.actor.Status.Success
import akka.actor._
import akka.pattern.ask
import akka.productfoundry.contrib.pattern.ReceivePipeline
import akka.util.Timeout
import com.productfoundry.akka.cqrs.process.ProcessManagerRegistryActor.Register
import com.productfoundry.akka.cqrs.publish.{EventPublication, EventPublicationInterceptor}
import com.productfoundry.akka.cqrs.{AggregateEventRecord, EntityContext, EntityIdResolution}

import scala.concurrent.Future
import scala.language.existentials
import scala.reflect.ClassTag

object ProcessManagerRegistry {
  def apply(actorRefFactory: ActorRefFactory, entityContext: EntityContext) = {
    new ProcessManagerRegistry(actorRefFactory, entityContext)
  }
}

/**
  * Keeps track of all active process managers and their event mappings.
  */
class ProcessManagerRegistry(actorRefFactory: ActorRefFactory, entityContext: EntityContext) {

  val actor = actorRefFactory.actorOf(Props[ProcessManagerRegistryActor], "ProcessManagerRegistry")

  def register[P <: ProcessManager : ProcessManagerFactory : EntityIdResolution : ClassTag](implicit timeout: Timeout): Future[Any] = {
    val supervisorFactory = entityContext.entitySupervisorFactory[P]
    val supervisorName = supervisorFactory.supervisorName
    val supervisorRef = supervisorFactory.getOrCreate
    val idResolution = implicitly[EntityIdResolution[P]]
    actor ? Register(supervisorName, supervisorRef, idResolution)
  }

  /**
    * Registers a process manager.
    *
    * Simplifies registering processes by using the companion to resolve dependencies rather than implicits.
    *
    * Usage:
    *
    * MyProcess.scala
    *
    * object MyProcess extends ProcessManagerCompanion[MyProcess] {
    *
    * ...
    *
    * def factory(dependency: Dependency, otherDependency: Dependency)
    * (implicit ec: ExecutionContext, timeout: Timeout) = new ProcessManagerFactory[MyProcess] {
    * override def props(config: PassivationConfig): Props = {
    * Props(new MyProcess(config, dependency, otherDependency))
    * }
    * }
    *
    * ...
    *
    * }
    *
    * Global.scala
    *
    * register(MyProcess.factory(dependency, otherDependency))
    *
    * @param factory to create the process manager, can be used to inject any dependency.
    * @param timeout receive timeout for the process manager.
    * @tparam P Process manager type.
    * @return Future that completes when the process manager is registered.
    */
  def register[P <: ProcessManager : ProcessManagerCompanion : ClassTag](factory: ProcessManagerFactory[P])(implicit timeout: Timeout): Future[Any] = {
    register[P](factory, implicitly[ProcessManagerCompanion[P]].idResolution, implicitly[ClassTag[P]], timeout)
  }
}

object ProcessManagerRegistryActor {

  case class Register(supervisorName: String, supervisorRef: ActorRef, idResolution: EntityIdResolution[_ <: ProcessManager])

}

class ProcessManagerRegistryActor
  extends Actor
  with ActorLogging
  with ReceivePipeline
  with EventPublicationInterceptor {

  import ProcessManagerRegistryActor._

  case class Registration(supervisorRef: ActorRef, idResolution: EntityIdResolution[_ <: ProcessManager])

  private var registrations = Map.empty[String, Registration]

  override def receive: Receive = {

    case Register(supervisorName, supervisorRef, idResolution) =>
      registrations = registrations.updated(supervisorName, Registration(supervisorRef, idResolution))
      sender() ! Success(Unit)

    case eventRecord: AggregateEventRecord =>

      // TODO [AK] Guaranteed delivery
      registrations.foreach { case (supervisorName, registration) =>
        try {
          val publication = EventPublication(eventRecord)
          if (registration.idResolution.entityIdResolver.isDefinedAt(publication)) {
            log.info("{} receives {}", supervisorName, eventRecord.tag)
            registration.supervisorRef.forward(publication)
          } else {
            log.debug("{} ignores {}", supervisorName, eventRecord.tag)
          }
        } catch {
          case e: Exception => log.error(e, "{} crashes handling {}", supervisorName, eventRecord.tag)
        }
      }
  }
}