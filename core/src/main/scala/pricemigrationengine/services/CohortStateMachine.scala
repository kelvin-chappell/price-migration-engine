package pricemigrationengine.services

import pricemigrationengine.model.{CohortSpec, CohortStateMachineFailure}
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse
import zio.ZIO

/** Kicks off the migration process for a particular cohort.
  *
  * The specification of the cohort is used as the input to the state machine.
  */
trait CohortStateMachine {

  /** Throws on failure. */
  def startExecution(spec: CohortSpec): StartExecutionResponse
}

object CohortStateMachine {

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  def startExecution(
      spec: CohortSpec
  ): ZIO[CohortStateMachine, CohortStateMachineFailure, StartExecutionResponse] =
    ZIO.serviceWithZIO(service =>
      ZIO
        .attempt(service.startExecution(spec))
        .mapError(ex => CohortStateMachineFailure(s"Failed to start execution: $ex"))
    )
}
