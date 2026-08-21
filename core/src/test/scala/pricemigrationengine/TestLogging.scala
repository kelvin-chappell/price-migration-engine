package pricemigrationengine

import pricemigrationengine.services.{ConsoleLogging, Logging}
import zio.ULayer

object TestLogging {
  val instance: Logging = ConsoleLogging.instance("TestCohort")

  /** ZIO-facing layer, kept only for handlers/services not yet converted to direct style. */
  val logging: ULayer[Logging] = ConsoleLogging.impl("TestCohort")
}
