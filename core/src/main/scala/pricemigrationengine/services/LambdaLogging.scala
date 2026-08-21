package pricemigrationengine.services

import build.BuildInfo.buildNumber
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import zio.{ULayer, ZLayer}

object LambdaLogging {

  /** Plain, direct-style instance - use this from any code not yet ZIO-wrapped. */
  def instance(context: Context, cohortName: String): Logging = new Logging {
    val logger: LambdaLogger = context.getLogger
    override def info(message: String): Unit =
      logger.log(s"(buildNumber: $buildNumber, cohortName: $cohortName) INFO: $message")
    override def error(message: String): Unit =
      logger.log(s"(buildNumber: $buildNumber, cohortName: $cohortName) ERROR: $message")
  }

  /** ZIO-facing layer, kept only for handlers/services not yet converted to direct style. */
  def impl(context: Context, cohortName: String): ULayer[Logging] = ZLayer.succeed(instance(context, cohortName))
}
