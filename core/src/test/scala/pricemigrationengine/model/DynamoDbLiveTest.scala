package pricemigrationengine.model

import pricemigrationengine.TestLogging
import pricemigrationengine.services._
import software.amazon.awssdk.services.dynamodb.model.AttributeAction.PUT
import software.amazon.awssdk.services.dynamodb.model._

import java.util
import scala.jdk.CollectionConverters._

class DynamoDbLiveTest extends munit.FunSuite {
  test("DynamoDbLive should get all batches of query results and convert the batches to an iterator") {
    def item(id: String) = Map("id" -> AttributeValue.builder.s(id).build()).asJava

    implicit val itemDeserialiser: DynamoDBDeserialiser[String] = new DynamoDBDeserialiser[String] {
      def deserialise(value: java.util.Map[String, AttributeValue]): Either[DynamoDbError, String] =
        value.asScala.get("id").map(_.s).toRight(DynamoDbError(""))
    }

    val queryRequest = QueryRequest.builder.build()
    val responseMap = Map(
      queryRequest -> QueryResponse.builder.items(item("id-1"), item("id-2")).lastEvaluatedKey(item("id-2")).build(),
      queryRequest.copy(x => x.exclusiveStartKey(item("id-2"))) -> QueryResponse.builder.items(item("id-3")).build()
    )
    val stubDynamoDBClient = new DynamoDBClient {
      def query(queryRequest: QueryRequest): QueryResponse = responseMap(queryRequest)

      def scan(scanRequest: ScanRequest): ScanResponse = ???

      def updateItem(updateRequest: UpdateItemRequest): UpdateItemResponse = ???

      def createItem(createRequest: PutItemRequest, keyName: String): PutItemResponse = ???

      def describeTable(tableName: String): DescribeTableResponse = ???

      def createTable(request: CreateTableRequest): CreateTableResponse = ???

      def updateContinuousBackups(request: UpdateContinuousBackupsRequest): UpdateContinuousBackupsResponse =
        ???
    }

    val results = DynamoDbLive.instance(stubDynamoDBClient, TestLogging.instance).query(queryRequest)
    assertEquals(results.toList, List(Right("id-1"), Right("id-2"), Right("id-3")))
  }

  test("DynamoDbLive serialize key and values and update in dynamodb") {
    implicit val keySerialiser: DynamoDBSerialiser[String] = new DynamoDBSerialiser[String] {
      override def serialise(key: String): util.Map[String, AttributeValue] =
        Map("key" -> AttributeValue.builder.s(key).build()).asJava
    }

    implicit val updateSerialiser: DynamoDBUpdateSerialiser[String] = new DynamoDBUpdateSerialiser[String] {
      override def serialise(value: String): util.Map[String, AttributeValueUpdate] =
        Map(
          "value" -> AttributeValueUpdate.builder
            .value(AttributeValue.builder.s(value).build())
            .action(PUT)
            .build()
        ).asJava
    }

    var receivedUpdateItemRequest: Option[UpdateItemRequest] = None

    val stubDynamoDBClient = new DynamoDBClient {

      override def query(queryRequest: QueryRequest): QueryResponse = ???

      override def scan(scanRequest: ScanRequest): ScanResponse = ???

      override def updateItem(updateItemRequest: UpdateItemRequest): UpdateItemResponse = {
        receivedUpdateItemRequest = Some(updateItemRequest)
        UpdateItemResponse.builder.build()
      }

      override def createItem(createRequest: PutItemRequest, keyName: String): PutItemResponse = ???

      override def describeTable(tableName: String): DescribeTableResponse = ???

      override def createTable(request: CreateTableRequest): CreateTableResponse = ???

      override def updateContinuousBackups(
          request: UpdateContinuousBackupsRequest
      ): UpdateContinuousBackupsResponse =
        ???
    }

    assertEquals(
      DynamoDbLive.instance(stubDynamoDBClient, TestLogging.instance).update("a-table", "key", "value"),
      Right(())
    )

    assertEquals(
      receivedUpdateItemRequest,
      Some(
        UpdateItemRequest.builder
          .tableName("a-table")
          .key(
            Map("key" -> AttributeValue.builder.s("key").build()).asJava
          )
          .attributeUpdates(
            Map(
              "value" -> AttributeValueUpdate.builder
                .value(AttributeValue.builder.s("value").build())
                .action(PUT)
                .build()
            ).asJava
          )
          .build()
      )
    )
  }

  test("DynamoDbLive.query yields a Left on a query failure, preserving the tracking code") {
    implicit val itemDeserialiser: DynamoDBDeserialiser[String] = _ => Right("unused")

    val queryRequest = QueryRequest.builder.build()
    val stubDynamoDBClient = new DynamoDBClient {
      def query(queryRequest: QueryRequest): QueryResponse = throw new RuntimeException("boom")
      def scan(scanRequest: ScanRequest): ScanResponse = ???
      def updateItem(updateRequest: UpdateItemRequest): UpdateItemResponse = ???
      def createItem(createRequest: PutItemRequest, keyName: String): PutItemResponse = ???
      def describeTable(tableName: String): DescribeTableResponse = ???
      def createTable(request: CreateTableRequest): CreateTableResponse = ???
      def updateContinuousBackups(request: UpdateContinuousBackupsRequest): UpdateContinuousBackupsResponse = ???
    }

    val results = DynamoDbLive.instance(stubDynamoDBClient, TestLogging.instance).query(queryRequest).toList
    assertEquals(
      results,
      List(Left(DynamoDbError(s"Failed to execute query $queryRequest : java.lang.RuntimeException: boom")))
    )
  }

  test("DynamoDbLive.create yields a Left carrying the original exception as its cause") {
    implicit val valueSerialiser: DynamoDBSerialiser[String] =
      value => Map("value" -> AttributeValue.builder.s(value).build()).asJava

    val underlyingException = new RuntimeException("already exists")
    val stubDynamoDBClient = new DynamoDBClient {
      def query(queryRequest: QueryRequest): QueryResponse = ???
      def scan(scanRequest: ScanRequest): ScanResponse = ???
      def updateItem(updateRequest: UpdateItemRequest): UpdateItemResponse = ???
      def createItem(createRequest: PutItemRequest, keyName: String): PutItemResponse = throw underlyingException
      def describeTable(tableName: String): DescribeTableResponse = ???
      def createTable(request: CreateTableRequest): CreateTableResponse = ???
      def updateContinuousBackups(request: UpdateContinuousBackupsRequest): UpdateContinuousBackupsResponse = ???
    }

    assertEquals(
      DynamoDbLive.instance(stubDynamoDBClient, TestLogging.instance).create("a-table", "key", "value"),
      Left(
        DynamoDbError(s"Failed to write value 'value' to 'a-table': $underlyingException", Some(underlyingException))
      )
    )
  }
}
