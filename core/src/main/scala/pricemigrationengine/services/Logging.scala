package pricemigrationengine.services

import pricemigrationengine.model.{CohortItem, Failure}
import zio.{UIO, ZIO}

/** Plain, direct-style logging service. `info`/`error` are ordinary synchronous methods; the ZIO-facing
  * accessors below (`Logging.info`, etc.) remain for as-yet-unconverted ZIO call sites, and are removed once
  * every caller has flipped to direct style. See docs/direct-style-migration.md.
  */
trait Logging {
  def info(s: String): Unit
  def error(s: String): Unit

  def logSuccess[A](cohortItem: CohortItem)(result: A): Unit =
    info(s"Subscription ${cohortItem.subscriptionName} succeeded: $result")

  def logFailure(cohortItem: CohortItem)(failure: Failure): Unit =
    error(s"Subscription ${cohortItem.subscriptionName} failed: $failure")
}

object Logging {

  // ZIO-facing accessors, kept only for handlers/services not yet converted to direct style.
  def info(s: String): ZIO[Logging, Nothing, Unit] =
    ZIO.environmentWithZIO(env => ZIO.succeed(env.get.info(s)))

  def error(s: String): ZIO[Logging, Nothing, Unit] =
    ZIO.environmentWithZIO(env => ZIO.succeed(env.get.error(s)))

  def logSuccess[A](cohortItem: CohortItem)(result: A): ZIO[Logging, Nothing, Unit] =
    ZIO.environmentWithZIO(env => ZIO.succeed(env.get.logSuccess(cohortItem)(result)))

  def logFailure(cohortItem: CohortItem)(failure: Failure): ZIO[Logging, Nothing, Unit] =
    ZIO.environmentWithZIO(env => ZIO.succeed(env.get.logFailure(cohortItem)(failure)))
}
