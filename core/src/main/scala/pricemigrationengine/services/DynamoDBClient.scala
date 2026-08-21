package pricemigrationengine.services

import software.amazon.awssdk.services.dynamodb.model.{
  CreateTableRequest,
  CreateTableResponse,
  DescribeTableResponse,
  PutItemRequest,
  PutItemResponse,
  QueryRequest,
  QueryResponse,
  ScanRequest,
  ScanResponse,
  UpdateContinuousBackupsRequest,
  UpdateContinuousBackupsResponse,
  UpdateItemRequest,
  UpdateItemResponse
}

trait DynamoDBClient {
  def query(queryRequest: QueryRequest): QueryResponse
  def scan(scanRequest: ScanRequest): ScanResponse
  def updateItem(updateRequest: UpdateItemRequest): UpdateItemResponse
  def createItem(createRequest: PutItemRequest, keyName: String): PutItemResponse
  def describeTable(tableName: String): DescribeTableResponse
  def createTable(request: CreateTableRequest): CreateTableResponse
  def updateContinuousBackups(request: UpdateContinuousBackupsRequest): UpdateContinuousBackupsResponse
}
