package pricemigrationengine.services

import munit.FunSuite
import pricemigrationengine.model.CohortTableFilter.ReadyForEstimation
import pricemigrationengine.model.{CohortItem, InputFailure}

import java.io.{ByteArrayOutputStream, PrintStream}

class ConsoleLoggingTest extends FunSuite {

  private def captureStdout(f: => Unit): String = {
    val out = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(out)) {
      f
    }
    out.toString
  }

  test("info prints the cohort name and message, prefixed INFO") {
    val logging = ConsoleLogging.instance("TestCohort")
    val printed = captureStdout {
      logging.info("hello")
    }
    assertEquals(printed.trim, "cohortName: TestCohort, INFO: hello")
  }

  test("error prints the cohort name and message, prefixed ERROR") {
    val logging = ConsoleLogging.instance("TestCohort")
    val printed = captureStdout {
      logging.error("oops")
    }
    assertEquals(printed.trim, "cohortName: TestCohort, ERROR: oops")
  }

  test("logSuccess logs the subscription name and result via info") {
    val logging = ConsoleLogging.instance("TestCohort")
    val item = CohortItem("sub-1", ReadyForEstimation)
    val printed = captureStdout {
      logging.logSuccess(item)("some-result")
    }
    assertEquals(printed.trim, "cohortName: TestCohort, INFO: Subscription sub-1 succeeded: some-result")
  }

  test("logFailure logs the subscription name and failure via error") {
    val logging = ConsoleLogging.instance("TestCohort")
    val item = CohortItem("sub-1", ReadyForEstimation)
    val printed = captureStdout {
      logging.logFailure(item)(InputFailure("bad input"))
    }
    assertEquals(printed.trim, "cohortName: TestCohort, ERROR: Subscription sub-1 failed: InputFailure(bad input)")
  }
}
