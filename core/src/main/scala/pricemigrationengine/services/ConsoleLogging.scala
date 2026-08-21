package pricemigrationengine.services

import zio.{ULayer, ZLayer}

object ConsoleLogging {

  /** Plain, direct-style instance - use this from any code not yet ZIO-wrapped. */
  def instance(cohortName: String): Logging = new Logging {
    override def info(s: String): Unit = println(s"cohortName: $cohortName, INFO: $s")
    override def error(s: String): Unit = println(s"cohortName: $cohortName, ERROR: $s")
  }

  /** ZIO-facing layer, kept only for handlers/services not yet converted to direct style. */
  def impl(cohortName: String): ULayer[Logging] = ZLayer.succeed(instance(cohortName))
}
