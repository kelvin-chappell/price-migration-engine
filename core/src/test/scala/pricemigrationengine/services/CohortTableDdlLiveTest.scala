package pricemigrationengine.services

import pricemigrationengine.TestLogging
import pricemigrationengine.model.{CohortSpec, CohortTableCreateFailure, StageConfig}
import software.amazon.awssdk.services.dynamodb.model._

import java.time.LocalDate

import scala.concurrent.duration._

/** Direct-style tests for `CohortTableDdlLive.instance`, calling it directly (no `unsafeRunSync`/ZIO
  * `Runtime`) - see docs/direct-style-migration.md.
  */
class CohortTableDdlLiveTest extends munit.FunSuite {

  private val cohortSpec = CohortSpec("cohortName", LocalDate.of(2022, 1, 1))
  private val stageConfig = StageConfig("DEV")
  private val tableName = cohortSpec.tableName(stageConfig.stage)

  private def stubClient(
      describeTableResponse: String => DescribeTableResponse = _ => DescribeTableResponse.builder.build(),
      createTableResponse: CreateTableRequest => CreateTableResponse = _ => CreateTableResponse.builder.build(),
      updateContinuousBackupsResponse: UpdateContinuousBackupsRequest => UpdateContinuousBackupsResponse = _ =>
        UpdateContinuousBackupsResponse.builder.build()
  ): DynamoDBClient =
    new DynamoDBClient {
      def query(queryRequest: QueryRequest): QueryResponse = ???
      def scan(scanRequest: ScanRequest): ScanResponse = ???
      def updateItem(updateRequest: UpdateItemRequest): UpdateItemResponse = ???
      def createItem(createRequest: PutItemRequest, keyName: String): PutItemResponse = ???
      def describeTable(name: String): DescribeTableResponse = describeTableResponse(name)
      def createTable(request: CreateTableRequest): CreateTableResponse = createTableResponse(request)
      def updateContinuousBackups(request: UpdateContinuousBackupsRequest): UpdateContinuousBackupsResponse =
        updateContinuousBackupsResponse(request)
    }

  test("createTable does nothing (and returns None) when the table already exists") {
    val client = stubClient()
    val ddl = CohortTableDdlLive.instance(client, stageConfig, TestLogging.instance)

    assertEquals(ddl.createTable(cohortSpec), Right(None))
  }

  test("createTable creates the table (and returns Some) when it doesn't already exist") {
    val response = CreateTableResponse.builder.build()
    val client = stubClient(
      describeTableResponse = _ => throw ResourceNotFoundException.builder.message("no such table").build(),
      createTableResponse = _ => response
    )
    val ddl = CohortTableDdlLive.instance(client, stageConfig, TestLogging.instance)

    assertEquals(ddl.createTable(cohortSpec), Right(Some(response)))
  }

  test("createTable surfaces a CohortTableCreateFailure, preserving the underlying exception, on create failure") {
    val client = stubClient(
      describeTableResponse = _ => throw ResourceNotFoundException.builder.message("no such table").build(),
      createTableResponse = _ => throw DynamoDbException.builder.message("create boom").build()
    )
    val ddl = CohortTableDdlLive.instance(client, stageConfig, TestLogging.instance)

    ddl.createTable(cohortSpec) match {
      case Left(CohortTableCreateFailure(reason)) => assert(reason.contains("create boom"))
      case other                                  => fail(s"Expected a Left(CohortTableCreateFailure), got $other")
    }
  }

  test("createTable surfaces a CohortTableCreateFailure once continuous-backups retries are exhausted") {
    val client = stubClient(
      updateContinuousBackupsResponse = _ => throw DynamoDbException.builder.message("backups boom").build()
    )
    val ddl = CohortTableDdlLive.instance(client, stageConfig, TestLogging.instance, initialRetryDelay = 0.millis)

    ddl.createTable(cohortSpec) match {
      case Left(CohortTableCreateFailure(reason)) => assert(reason.contains("backups boom"))
      case other                                  => fail(s"Expected a Left(CohortTableCreateFailure), got $other")
    }
  }
}
