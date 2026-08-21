package pricemigrationengine.services

import munit.FunSuite

import scala.concurrent.duration._

class RetryTest extends FunSuite {

  test("retry returns the result on first success without retrying") {
    var attempts = 0
    val result = Retry.retry(times = 3, delay = 0.millis) {
      attempts += 1
      "ok"
    }
    assertEquals(result, "ok")
    assertEquals(attempts, 1)
  }

  test("retry retries on failure and succeeds once the underlying call succeeds") {
    var attempts = 0
    val result = Retry.retry(times = 3, delay = 0.millis) {
      attempts += 1
      if (attempts < 3) throw new RuntimeException("transient")
      "eventually ok"
    }
    assertEquals(result, "eventually ok")
    assertEquals(attempts, 3)
  }

  test("retry gives up and rethrows after exhausting all attempts") {
    var attempts = 0
    val ex = intercept[RuntimeException] {
      Retry.retry(times = 3, delay = 0.millis) {
        attempts += 1
        throw new RuntimeException("always fails")
      }
    }
    assertEquals(ex.getMessage, "always fails")
    // 1 initial call + 3 retries = 4 total attempts
    assertEquals(attempts, 4)
  }

  test("pollUntil returns immediately once the done predicate is satisfied") {
    var attempts = 0
    val result = Retry.pollUntil(maxAttempts = 5, delay = 0.millis) {
      attempts += 1
      attempts
    }(done = _ >= 3)
    assertEquals(result, 3)
    assertEquals(attempts, 3)
  }

  test("pollUntil throws once maxAttempts is exhausted without satisfying done") {
    var attempts = 0
    intercept[RuntimeException] {
      Retry.pollUntil(maxAttempts = 3, delay = 0.millis) {
        attempts += 1
        attempts
      }(done = _ >= 100)
    }
    assertEquals(attempts, 3)
  }
}
