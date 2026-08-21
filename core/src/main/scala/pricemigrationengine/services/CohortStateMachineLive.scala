package pricemigrationengine.services

import pricemigrationengine.model._
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest
import upickle.default.{ReadWriter, macroRW, write}
import zio.{ZIO, ZLayer}

import java.time.{Clock, Instant, ZoneId}
import java.time.format.DateTimeFormatter

object CohortStateMachineLive {

  private case class StateMachineInput(cohortSpec: CohortSpec)

  private implicit val rw: ReadWriter[StateMachineInput] = macroRW

  /** Pure: builds the state-machine execution request from a spec and a (pre-fetched) instant. No I/O. */
  private[services] def buildRequest(
      config: CohortStateMachineConfig,
      spec: CohortSpec,
      now: Instant
  ): StartExecutionRequest = {
    val timeStr = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm").withZone(ZoneId.systemDefault).format(now)
    StartExecutionRequest.builder
      .stateMachineArn(config.stateMachineArn)
      .name(s"${spec.cohortName}-$timeStr")
      .input(write(StateMachineInput(spec)))
      .build()
  }

  /** Plain, direct-style implementation. Throws on failure. All I/O (clock, AWS call, logging) is confined to
    * this method; request-building itself is pure (see `buildRequest`).
    */
  def instance(
      config: CohortStateMachineConfig,
      logging: Logging,
      clock: Clock = Clock.systemUTC(),
      stateMachine: SfnClient = AwsClient.sfn
  ): CohortStateMachine = spec => {
    logging.info(s"Starting execution with input: ${spec.toString} ...")
    val request = buildRequest(config, spec, clock.instant())
    val result = stateMachine.startExecution(request)
    logging.info(s"Started execution: $result")
    result
  }

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  val impl: ZLayer[CohortStateMachineConfig with Logging, ConfigFailure, CohortStateMachine] =
    ZLayer.fromZIO {
      for {
        logging <- ZIO.service[Logging]
        config <- ZIO.service[CohortStateMachineConfig]
      } yield instance(config, logging)
    }
}
