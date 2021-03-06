package com.productfoundry.akka.cqrs

import java.util.UUID

import akka.actor._
import com.productfoundry.akka.cqrs.AggregateStatus.AggregateStatus
import com.productfoundry.akka.cqrs.CommandRequest._
import org.scalatest._

import scala.concurrent.stm._
import scala.reflect.ClassTag
import scala.util.Random

/**
 * Base spec for testing aggregates.
 * @param _system test actor system.
 * @param aggregateClass aggregate class.
 * @param aggregateFactory aggregate factory, typically defined in the Spec to mixin additional behavior.
 * @tparam A Aggregate type.
 */
abstract class AggregateSupport[A <: Aggregate](_system: ActorSystem)(implicit aggregateClass: ClassTag[A],
                                                                      aggregateFactory: AggregateFactory[A])
  extends EntitySupport(_system) {

  implicit def entityIdResolution: EntityIdResolution[A] = new AggregateIdResolution[A]()

  /**
   * Test local entities by default, requires implicit entity factory.
   */
  implicit val supervisorFactory = new LocalEntityContext(system).entitySupervisorFactory[A]

  /**
   * Entity supervisor for the actor under test.
   */
  var supervisor: ActorRef = system.deadLetters

  /**
   * Commits are collected only if the LocalCommitPublisher is mixed into the actor under test.
   */
  def withCommitCollector[E](block: (LocalCommitCollector) => E): E = {
    block(commitCollector)
  }

  /**
   * Optionally collected commits.
   */
  var commitCollectorOption: Option[LocalCommitCollector] = None

  /**
   * Collected commits.
   */
  def commitCollector: LocalCommitCollector = commitCollectorOption.getOrElse(throw new IllegalArgumentException("Commit collector is not yet available"))

  /**
   * Initialize the supervisor.
   */
  before {
    supervisor = EntitySupervisor.forType[A]
    commitCollectorOption = Some(LocalCommitCollector(UUID.randomUUID().toString))
  }

  /**
   * Terminates all actors.
   */
  after {
    terminateConfirmed(supervisor)

    withCommitCollector { commitCollector =>
      terminateConfirmed(commitCollector.ref)
      commitCollectorOption = None
    }
  }

  /**
   * Dump commits on failure when collected.
   * @param test to run.
   * @return outcome.
   */
  override protected def withFixture(test: NoArgTest): Outcome = {
    val outcome = super.withFixture(test)

    withCommitCollector { commitCollector =>
      if (outcome.isFailed) {
        commitCollector.dumpCommits()
      }
    }

    outcome
  }

  /**
   * Asserts a specified event is committed.
   * @param event that is expected.
   * @param CommitTag indicates commit type with events.
   */
  def expectEvent(event: AggregateEvent)(implicit CommitTag: ClassTag[Commit]): Unit = {
    eventually {
      withCommitCollector { commitCollector =>
        assert(commitCollector.events.contains(event), s"Commit with event $event not found, does the aggregate under test have the LocalCommitPublisher mixin?")
      }
    }
  }

  /**
   * Asserts an event is committed that matches the specified partial function.
   *
   * For all matching events, an assertion can be executed.
   *
   * @param eventCheckFunction to match and assert events.
   */
  def expectEventPF(eventCheckFunction: PartialFunction[AggregateEvent, Unit]): Unit = {
    eventually {
      withCommitCollector { commitCollector =>
        val events = commitCollector.events
        val toCheck = events.filter(eventCheckFunction.isDefinedAt)
        assert(toCheck.nonEmpty, s"No events match provided partial function: $events")
        toCheck.foreach(eventCheckFunction)
      }
    }
  }

  /**
   * Maps a matching event to a value.
   * @param eventMapFunction to map an event to a value.
   */
  def mapEventPF[E](eventMapFunction: PartialFunction[AggregateEvent, E]): E = {
    eventually {
      withCommitCollector { commitCollector =>
        val events = commitCollector.events
        val toCheck = events.filter(eventMapFunction.isDefinedAt)
        assert(toCheck.size == 1, s"Other than 1 event matches provided partial function: $events")
        toCheck.map(eventMapFunction).head
      }
    }
  }

  /**
   * Asserts a success message is sent from the aggregate.
   * @return the success message.
   */
  def expectMsgSuccess: AggregateStatus.Success = {
    expectMsgType[AggregateStatus.Success]
  }

  /**
   * Asserts a failure message is sent from the aggregate.
   * @param t wrapped error type tag.
   * @tparam T wrapped error type.
   * @return the error wrapped in the failure message.
   */
  def expectMsgError[T](implicit t: ClassTag[T]): T = {
    expectMsgType[AggregateStatus.Failure].cause.asInstanceOf[T]
  }

  /**
   * Asserts a validation error is sent from the aggregate.
   * @param message the expected validation message.
   */
  def expectMsgValidationError(message: ValidationMessage) = {
    assertValidationError(message, expectMsgType[AggregateStatus])
  }

  /**
   * Asserts a status contains a failure message.
   * @param message the expected failure message.
   * @param status the status.
   */
  def assertValidationError(message: ValidationMessage, status: AggregateStatus): Unit = {
    status match {
      case success: AggregateStatus.Success =>
        fail(s"Unexpected success: $success")

      case AggregateStatus.Failure(cause) =>
        cause match {
          case ValidationError(messages) =>
            assert(Seq(message) === messages, s"Unexpected messages: $messages")

          case _ =>
            fail(s"Unexpected cause: $cause")
        }
    }
  }

  /**
   * Asserts a status contains a failure.
   * @tparam C the expected failure class.
   * @param status the status.
   */
  def assertFailure[C: ClassTag](status: AggregateStatus): Unit = {
    status match {
      case success: AggregateStatus.Success => fail(s"Unexpected success: $success")
      case AggregateStatus.Failure(cause: C) =>
      case AggregateStatus.Failure(cause) => fail(s"Unexpected cause: $cause")
    }
  }

  /**
   * Scoped fixture to setup aggregates and send messages while keeping track of revisions.
   */
  trait AggregateFixture {
    val revisionRef = Ref(AggregateRevision.Initial)

    /**
     * Use commands to initialize fixture state, asserts that all commands return success.
     *
     * Can be invoked multiple times.
     *
     * @param commands to send to aggregate, must succeed,
     */
    def given(commands: AggregateCommand*): Unit = {
      atomic { implicit txn =>
        revisionRef.transform { revision =>
          commands.foldLeft(revision) { case (rev, command) =>
            supervisor ! command.withExpectedRevision(rev)
            expectMsgSuccess.response.tag.revision
          }
        }
      }
    }

    /**
     * Executes the specified command and returns the status from the aggregate.
     *
     * @param cmd to execute.
     * @return status.
     */
    def command(cmd: AggregateCommand): AggregateStatus = {
      atomic { implicit txn =>
        val statusOptionRef: Ref[Option[AggregateStatus]] = Ref(None)

        revisionRef.transform { revision =>
          supervisor ! cmd.withExpectedRevision(revision)
          expectMsgPF() {
            case success: AggregateStatus.Success =>
              statusOptionRef.set(Some(success))
              success.response.tag.revision

            case failure@AggregateStatus.Failure(_) =>
              statusOptionRef.set(Some(failure))
              revision
          }
        }

        statusOptionRef().getOrElse(throw new RuntimeException("Unexpected status"))
      }
    }
  }

}
