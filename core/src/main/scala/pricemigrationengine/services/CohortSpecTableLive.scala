package pricemigrationengine.services

import pricemigrationengine.model._
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import zio.{ZIO, ZLayer}

import scala.jdk.CollectionConverters._
import scala.util.{Failure => TryFailure, Success, Try}

object CohortSpecTableLive {

  private val tableNamePrefix = "price-migration-engine-cohort-spec"

  /** Plain, direct-style implementation. */
  def instance(dynamoDbClient: DynamoDBClient, stageConfig: StageConfig, logging: Logging): CohortSpecTable =
    new CohortSpecTable {

      override def fetchAll(): Either[Failure, Set[CohortSpec]] = {
        val scanRequest = ScanRequest.builder.tableName(s"$tableNamePrefix-${stageConfig.stage}").build()

        val result: Either[Failure, Set[CohortSpec]] = for {
          scanResult <- Try(dynamoDbClient.scan(scanRequest)) match {
            case Success(response) => Right(response)
            case TryFailure(e)     => Left(CohortSpecFetchFailure(s"Failed to fetch cohort specs: $e"))
          }
          specs <- scanResult.items.asScala.toList
            .foldLeft[Either[Failure, List[CohortSpec]]](Right(Nil)) { (acc, item) =>
              for {
                soFar <- acc
                spec <- CohortSpec
                  .fromDynamoDbItem(item)
                  .left
                  .map(e => CohortSpecFetchFailure(s"Failed to parse '$item': ${e.reason}"))
              } yield spec :: soFar
            }
        } yield specs.toSet

        result.foreach(specs => logging.info(s"Fetched ${specs.size} cohort specs"))
        result
      }

      override def update(spec: CohortSpec): Either[CohortSpecUpdateFailure, Unit] =
        Left(CohortSpecUpdateFailure("No implementation yet!"))
    }

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  val impl: ZLayer[DynamoDBClient with StageConfig with Logging, ConfigFailure, CohortSpecTable] =
    ZLayer.fromZIO(for {
      logging <- ZIO.service[Logging]
      stageConfig <- ZIO.service[StageConfig]
      dynamoDbClient <- ZIO.service[DynamoDBClient]
    } yield instance(dynamoDbClient, stageConfig, logging))
}
