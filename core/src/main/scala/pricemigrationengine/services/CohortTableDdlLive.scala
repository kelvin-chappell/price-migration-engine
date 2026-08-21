package pricemigrationengine.services

import pricemigrationengine.model.{CohortSpec, CohortTableCreateFailure, ConfigFailure, StageConfig}
import software.amazon.awssdk.services.dynamodb.model.BillingMode.PAY_PER_REQUEST
import software.amazon.awssdk.services.dynamodb.model.KeyType.{HASH, RANGE}
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType.S
import software.amazon.awssdk.services.dynamodb.model._
import zio._

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object CohortTableDdlLive {

  private val partitionKey = "subscriptionNumber"

  private val stageIndex = "ProcessingStageIndexV2"
  private val stageAndDateIndex = "ProcessingStageAndDateIndexV1"

  private val stageAttribute = "processingStage"
  private val amendmentEffectiveDateAttribute = "amendmentEffectiveDate"

  /** Builds the (pure) request describing the table to create for `tableName`. */
  private def createTableRequest(tableName: String): CreateTableRequest =
    CreateTableRequest.builder
      .tableName(tableName)
      .keySchema(KeySchemaElement.builder.attributeName(partitionKey).keyType(HASH).build())
      .attributeDefinitions(
        AttributeDefinition.builder.attributeName(partitionKey).attributeType(S).build(),
        AttributeDefinition.builder.attributeName(stageAttribute).attributeType(S).build(),
        AttributeDefinition.builder
          .attributeName(amendmentEffectiveDateAttribute)
          .attributeType(S)
          .build()
      )
      .globalSecondaryIndexes(
        GlobalSecondaryIndex.builder
          .indexName(stageIndex)
          .keySchema(KeySchemaElement.builder.attributeName(stageAttribute).keyType(HASH).build())
          .projection(Projection.builder.projectionType(ProjectionType.ALL).build())
          .build(),
        GlobalSecondaryIndex.builder
          .indexName(stageAndDateIndex)
          .keySchema(
            KeySchemaElement.builder.attributeName(stageAttribute).keyType(HASH).build(),
            KeySchemaElement.builder.attributeName(amendmentEffectiveDateAttribute).keyType(RANGE).build()
          )
          .projection(Projection.builder.projectionType(ProjectionType.ALL).build())
          .build()
      )
      .billingMode(PAY_PER_REQUEST)
      .build()

  /** Builds the (pure) request enabling point-in-time-recovery continuous backups for `tableName`. */
  private def enableContinuousBackupsRequest(tableName: String): UpdateContinuousBackupsRequest =
    UpdateContinuousBackupsRequest.builder
      .tableName(tableName)
      .pointInTimeRecoverySpecification(
        PointInTimeRecoverySpecification.builder.pointInTimeRecoveryEnabled(true).build()
      )
      .build()

  /** Plain, direct-style implementation. Enabling continuous backups is retried with exponential backoff (up
    * to 9 total attempts, matching the original `Schedule.exponential(1.second) && Schedule.recurs(8)`),
    * logging a message via `logging` before each retry, matching the original's `tapError`. `initialRetryDelay`
    * defaults to the production value but can be overridden (e.g. to `0.millis` in tests) to avoid real sleeps.
    */
  def instance(
      dynamoDbClient: DynamoDBClient,
      stageConfig: StageConfig,
      logging: Logging,
      initialRetryDelay: FiniteDuration = FiniteDuration(1, "second")
  ): CohortTableDdl =
    new CohortTableDdl {

      private def create(tableName: String): Either[CohortTableCreateFailure, CreateTableResponse] =
        Try(dynamoDbClient.createTable(createTableRequest(tableName))) match {
          case Success(response) => Right(response)
          case Failure(ex)       => Left(CohortTableCreateFailure(ex.toString))
        }

      private def enableContinuousBackups(tableName: String): Either[CohortTableCreateFailure, Unit] = {
        val request = enableContinuousBackupsRequest(tableName)

        def attempt(attemptsLeft: Int, delay: FiniteDuration): Either[CohortTableCreateFailure, Unit] =
          Try(dynamoDbClient.updateContinuousBackups(request)) match {
            case Success(_)  => Right(())
            case Failure(ex) =>
              if (attemptsLeft <= 0) Left(CohortTableCreateFailure(ex.toString))
              else {
                logging.info(s"Waiting to enable continuous backups ...")
                Thread.sleep(delay.toMillis)
                attempt(attemptsLeft - 1, delay * 2)
              }
          }

        attempt(attemptsLeft = 8, delay = initialRetryDelay)
      }

      override def createTable(
          cohortSpec: CohortSpec
      ): Either[CohortTableCreateFailure, Option[CreateTableResponse]] = {
        val tableName = cohortSpec.tableName(stageConfig.stage)

        // if table can be described, it must already exist and therefore not need to be created
        val result: Either[CohortTableCreateFailure, Option[CreateTableResponse]] =
          Try(dynamoDbClient.describeTable(tableName)) match {
            case Success(_) => Right(None)
            case Failure(_) => create(tableName).map(Some(_))
          }

        for {
          created <- result
          _ <- enableContinuousBackups(tableName)
        } yield created
      }
    }

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  val impl: ZLayer[DynamoDBClient with StageConfig with Logging, ConfigFailure, CohortTableDdl] =
    ZLayer.fromZIO(
      for {
        logging <- ZIO.service[Logging]
        stageConfig <- ZIO.service[StageConfig]
        dynamoDbClient <- ZIO.service[DynamoDBClient]
      } yield instance(dynamoDbClient, stageConfig, logging)
    )
}
