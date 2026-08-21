package pricemigrationengine.services

import pricemigrationengine.TestLogging
import pricemigrationengine.model.{CohortSpec, CohortStateMachineConfig}
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.{StartExecutionRequest, StartExecutionResponse}

import java.time.{Clock, Instant, LocalDate, ZoneOffset}

class CohortStateMachineLiveTest extends munit.FunSuite {

  private val config = CohortStateMachineConfig("a-state-machine-arn")
  private val spec = CohortSpec("cohortName", LocalDate.of(2020, 1, 1))

  test("buildRequest is pure: builds the same request for the same inputs, with no I/O") {
    val now = Instant.parse("2024-03-05T10:15:30.00Z")

    val request = CohortStateMachineLive.buildRequest(config, spec, now)

    assertEquals(request.stateMachineArn, "a-state-machine-arn")
    assertEquals(request.name, "cohortName-2024-03-05-10-15")
    assert(request.input.contains("cohortName"))
  }

  test("buildRequest derives the execution name from the injected instant, not the wall clock") {
    val earlier = CohortStateMachineLive.buildRequest(config, spec, Instant.parse("2020-01-01T00:00:00.00Z"))
    val later = CohortStateMachineLive.buildRequest(config, spec, Instant.parse("2030-01-01T00:00:00.00Z"))

    assertEquals(earlier.name, "cohortName-2020-01-01-00-00")
    assertEquals(later.name, "cohortName-2030-01-01-00-00")
  }

  test("instance.startExecution uses the injected Clock (not the wall clock) and returns the SFN response") {
    val fixedInstant = Instant.parse("2024-03-05T10:15:30.00Z")
    val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    var receivedRequest: Option[StartExecutionRequest] = None
    val stubSfnClient: SfnClient = new SfnClient {
      override def close(): Unit = ()
      override def serviceName(): String = "sfn"
      override def startExecution(request: StartExecutionRequest): StartExecutionResponse = {
        receivedRequest = Some(request)
        StartExecutionResponse.builder.build()
      }
    }

    val cohortStateMachine =
      CohortStateMachineLive.instance(config, TestLogging.instance, fixedClock, stubSfnClient)

    cohortStateMachine.startExecution(spec)

    assertEquals(receivedRequest.map(_.name), Some("cohortName-2024-03-05-10-15"))
  }
}
