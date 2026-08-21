package pricemigrationengine.services

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

/** Plain, direct-style replacements for the ZIO `Schedule`-based retry/poll logic previously used at 4 call
  * sites (Zuora GETs, Zuora job-status long-poll, DDL continuous-backups retry, Amendment order-payload
  * retry). See docs/direct-style-migration.md.
  */
object Retry {

  /** Retries `f` up to `times` additional times (so up to `times + 1` total attempts) if it throws, waiting
    * `delay` between attempts. Rethrows the last exception once attempts are exhausted.
    */
  def retry[A](times: Int, delay: FiniteDuration)(f: => A): A = {
    try {
      f
    } catch {
      case e: Exception =>
        if (times <= 0) throw e
        else {
          if (delay.toMillis > 0) Thread.sleep(delay.toMillis)
          retry(times - 1, delay)(f)
        }
    }
  }

  /** Polls `poll` up to `maxAttempts` times, waiting `delay` between attempts, until `done` is satisfied by the
    * result. Throws once `maxAttempts` is exhausted without `done` being satisfied.
    */
  def pollUntil[A](maxAttempts: Int, delay: FiniteDuration)(poll: => A)(done: A => Boolean): A = {
    require(maxAttempts > 0, "maxAttempts must be positive")

    @tailrec
    def loop(attemptsLeft: Int): A = {
      val result = poll
      if (done(result)) result
      else if (attemptsLeft <= 1) throw new RuntimeException(s"Gave up polling after $maxAttempts attempts")
      else {
        if (delay.toMillis > 0) Thread.sleep(delay.toMillis)
        loop(attemptsLeft - 1)
      }
    }

    loop(maxAttempts)
  }
}
