package pricemigrationengine.services

import pricemigrationengine.TestLogging
import pricemigrationengine.model.{CohortSpec, CohortSpecFetchFailure, StageConfig}
import software.amazon.awssdk.services.dynamodb.model._

import java.time.LocalDate
import java.util
import scala.jdk.CollectionConverters._

/** Direct-style tests for `CohortSpecTableLive.instance`, calling it directly (no `unsafeRunSync`/ZIO
  * `Runtime`) - see docs/direct-style-migration.md.
  */
class CohortSpecTableLiveTest extends munit.FunSuite {

  private val stageConfig = StageConfig("DEV")

  private def item(cohortName: String, earliestAmendmentEffectiveDate: LocalDate): util.Map[String, AttributeValue] =
    Map(
      "cohortName" -> AttributeValue.builder.s(cohortName).build(),
      "earliestAmendmentEffectiveDate" -> AttributeValue.builder.s(earliestAmendmentEffectiveDate.toString).build()
    ).asJava

  private def stubClient(scanResponse: ScanRequest => ScanResponse): DynamoDBClient =
    new DynamoDBClient {
      def query(queryRequest: QueryRequest): QueryResponse = ???
      def scan(scanRequest: ScanRequest): ScanResponse = scanResponse(scanRequest)
      def updateItem(updateRequest: UpdateItemRequest): UpdateItemResponse = ???
      def createItem(createRequest: PutItemRequest, keyName: String): PutItemResponse = ???
      def describeTable(tableName: String): DescribeTableResponse = ???
      def createTable(request: CreateTableRequest): CreateTableResponse = ???
      def updateContinuousBackups(request: UpdateContinuousBackupsRequest): UpdateContinuousBackupsResponse = ???
    }

  test("fetchAll returns the parsed specs from a successful scan") {
    val date = LocalDate.of(2022, 1, 1)
    val response = ScanResponse.builder.items(item("cohort1", date), item("cohort2", date)).build()
    val table = CohortSpecTableLive.instance(stubClient(_ => response), stageConfig, TestLogging.instance)

    assertEquals(table.fetchAll(), Right(Set(CohortSpec("cohort1", date), CohortSpec("cohort2", date))))
  }

  test("fetchAll surfaces a CohortSpecFetchFailure, preserving the underlying exception, on scan failure") {
    val table = CohortSpecTableLive.instance(
      stubClient(_ => throw DynamoDbException.builder.message("scan boom").build()),
      stageConfig,
      TestLogging.instance
    )

    table.fetchAll() match {
      case Left(CohortSpecFetchFailure(reason)) => assert(reason.contains("scan boom"))
      case other                                => fail(s"Expected a Left(CohortSpecFetchFailure), got $other")
    }
  }

  test("fetchAll surfaces a CohortSpecFetchFailure, including the offending item, on a deserialisation failure") {
    val badItem: util.Map[String, AttributeValue] = Map.empty[String, AttributeValue].asJava
    val response = ScanResponse.builder.items(badItem).build()
    val table = CohortSpecTableLive.instance(stubClient(_ => response), stageConfig, TestLogging.instance)

    table.fetchAll() match {
      case Left(CohortSpecFetchFailure(reason)) => assert(reason.contains("Failed to parse"))
      case other                                => fail(s"Expected a Left(CohortSpecFetchFailure), got $other")
    }
  }

  test("update returns a Left, since it isn't implemented yet") {
    val table = CohortSpecTableLive.instance(stubClient(_ => ???), stageConfig, TestLogging.instance)

    assertEquals(
      table.update(CohortSpec("cohort1", LocalDate.of(2022, 1, 1))).isLeft,
      true
    )
  }
}
