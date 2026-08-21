package pricemigrationengine.services

import pricemigrationengine.model._
import zio.{Layer, ZIO, ZLayer}

import java.lang.System.getenv

/** Configuration settings found in system environment.
  */
object EnvConfig {

  /** Default environment lookup, used by every `instance(...)` factory below unless a test supplies its own. */
  private val defaultLookup: String => Option[String] = name => Option(getenv(name))

  private def env(lookup: String => Option[String])(name: String): String =
    lookup(name).getOrElse(throw new RuntimeException(s"No value for '$name' in environment"))

  object zuora {
    def instance(secrets: EngineSecrets): ZuoraConfig =
      ZuoraConfig(secrets.zuoraApiHost, secrets.zuoraClientId, secrets.zuoraClientSecret)

    def instance(): ZuoraConfig = instance(EngineSecrets.getSecretsPlain())

    val layer: Layer[ConfigFailure, ZuoraConfig] =
      ZLayer(ZIO.attempt(instance()).mapError(ex => ConfigFailure(ex.getMessage)))
  }

  object cohortTable {
    def instance(lookup: String => Option[String]): CohortTableConfig =
      CohortTableConfig(env(lookup)("batchSize").toInt)

    def instance(): CohortTableConfig = instance(defaultLookup)

    val layer: Layer[ConfigFailure, CohortTableConfig] =
      ZLayer(ZIO.attempt(instance()).mapError(ex => ConfigFailure(ex.getMessage)))
  }

  object salesforce {
    def instance(secrets: EngineSecrets): SalesforceConfig =
      SalesforceConfig(
        secrets.salesforceAuthUrl,
        secrets.salesforceClientId,
        secrets.salesforceClientSecret,
        secrets.salesforceUserName,
        secrets.salesforcePassword,
        secrets.salesforceToken
      )

    def instance(): SalesforceConfig = instance(EngineSecrets.getSecretsPlain())

    val layer: Layer[ConfigFailure, SalesforceConfig] =
      ZLayer(ZIO.attempt(instance()).mapError(ex => ConfigFailure(ex.getMessage)))
  }

  object stage {
    def instance(lookup: String => Option[String]): StageConfig =
      StageConfig(env(lookup)("stage"))

    def instance(): StageConfig = instance(defaultLookup)

    val layer: Layer[ConfigFailure, StageConfig] =
      ZLayer(ZIO.attempt(instance()).mapError(ex => ConfigFailure(ex.getMessage)))
  }

  object emailSender {
    def instance(lookup: String => Option[String]): EmailSenderConfig =
      EmailSenderConfig(env(lookup)("sqsEmailQueueName"))

    def instance(): EmailSenderConfig = instance(defaultLookup)

    val layer: Layer[ConfigFailure, EmailSenderConfig] =
      ZLayer(ZIO.attempt(instance()).mapError(ex => ConfigFailure(ex.getMessage)))
  }

  object cohortStateMachine {
    def instance(lookup: String => Option[String]): CohortStateMachineConfig =
      CohortStateMachineConfig(env(lookup)("cohortStateMachineArn"))

    def instance(): CohortStateMachineConfig = instance(defaultLookup)

    val layer: Layer[ConfigFailure, CohortStateMachineConfig] =
      ZLayer(ZIO.attempt(instance()).mapError(ex => ConfigFailure(ex.getMessage)))
  }

  object `export` {
    def instance(lookup: String => Option[String]): ExportConfig =
      ExportConfig(env(lookup)("exportBucketName"))

    def instance(): ExportConfig = instance(defaultLookup)

    val layer: Layer[ConfigFailure, ExportConfig] =
      ZLayer(ZIO.attempt(instance()).mapError(ex => ConfigFailure(ex.getMessage)))
  }
}
