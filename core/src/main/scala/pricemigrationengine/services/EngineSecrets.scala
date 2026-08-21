package pricemigrationengine.services

import pricemigrationengine.model._
import zio.{Layer, ZIO, ZLayer}
import zio._
import software.amazon.awssdk.regions
import software.amazon.awssdk.services.secretsmanager._
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import upickle.default._

case class EngineSecrets(
    zuoraApiHost: String,
    zuoraClientId: String,
    zuoraClientSecret: String,
    salesforceClientId: String,
    salesforceClientSecret: String,
    salesforceUserName: String,
    salesforcePassword: String,
    salesforceToken: String,
    salesforceAuthUrl: String
)

object EngineSecrets {

  implicit val reader: Reader[EngineSecrets] = macroRW

  private lazy val region: regions.Region = regions.Region.EU_WEST_1

  private lazy val secretsClient = SecretsManagerClient.create()

  private def getSecretIdPlain(): String = {
    val stage =
      Option(java.lang.System.getenv("stage")).getOrElse(throw new RuntimeException("Failure to retrieve stage"))
    s"price-migration-engine-lambda-${stage}"
  }

  private def getSecretStringPlain(): String = {
    val secretId = getSecretIdPlain()
    try {
      secretsClient.getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build()).secretString()
    } catch {
      case ex: Exception =>
        throw new RuntimeException(s"Failure to retrieve secrets string: ${ex.getMessage}", ex)
    }
  }

  /** Plain, direct-style version of [[getSecrets]]. Throws a `RuntimeException` on failure.
    */
  def getSecretsPlain(): EngineSecrets = {
    val secretJsonString = getSecretStringPlain()
    try {
      read[EngineSecrets](secretJsonString)
    } catch {
      case ex: Exception =>
        throw new RuntimeException(s"Failure to parse secrets string: ${ex.getMessage}", ex)
    }
  }

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  def getSecrets: ZIO[Any, ConfigFailure, EngineSecrets] =
    ZIO.attempt(getSecretsPlain()).mapError(ex => ConfigFailure(ex.getMessage))
}
