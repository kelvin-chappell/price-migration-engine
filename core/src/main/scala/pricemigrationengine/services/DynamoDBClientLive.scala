package pricemigrationengine.services

import pricemigrationengine.model.ConfigFailure
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import zio._

object DynamoDBClientLive {

  /** Plain, direct-style implementation, backed by the shared `AwsClient.dynamoDb` client.
    *
    * Note: the underlying `DynamoDbClient` is intentionally never shut down - see the comment on the previous
    * `releaseDynamoDb` for the historical reason (shutting it down while still in use led to
    * `IllegalStateException: Connection pool shut down`).
    */
  def instance(dynamoDb: DynamoDbClient = AwsClient.dynamoDb): DynamoDBClient = new DynamoDBClient {
    def query(queryRequest: QueryRequest): QueryResponse = dynamoDb.query(queryRequest)

    def scan(scanRequest: ScanRequest): ScanResponse = dynamoDb.scan(scanRequest)

    def updateItem(updateRequest: UpdateItemRequest): UpdateItemResponse = dynamoDb.updateItem(updateRequest)

    def createItem(createRequest: PutItemRequest, keyName: String): PutItemResponse =
      dynamoDb.putItem(createRequest.copy(x => x.conditionExpression(s"attribute_not_exists($keyName)")))

    def describeTable(tableName: String): DescribeTableResponse =
      dynamoDb.describeTable(DescribeTableRequest.builder.tableName(tableName).build())

    def createTable(request: CreateTableRequest): CreateTableResponse = dynamoDb.createTable(request)

    def updateContinuousBackups(request: UpdateContinuousBackupsRequest): UpdateContinuousBackupsResponse =
      dynamoDb.updateContinuousBackups(request)
  }

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  val impl: ZLayer[Any, ConfigFailure, DynamoDBClient] =
    ZLayer(ZIO.attempt(instance()).mapError(ex => ConfigFailure(s"Failed to create the dynamoDb client: $ex")))
}
