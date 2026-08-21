package pricemigrationengine.services

import software.amazon.awssdk.services.dynamodb.model._
import zio.{ZIO, ZLayer}

import java.util
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object DynamoDbLive {

  /** Plain, direct-style implementation. `query`/`scan` return a lazy `Iterator` that fetches each page from
    * DynamoDB only as it's consumed - mirroring the original `ZStream.unfoldZIO`'s lazy-pagination behaviour,
    * without a streaming library - yielding a `Left` (and then stopping) as soon as a page-fetch or
    * deserialisation failure is encountered. `update`/`create` return `Either[DynamoDbError, Unit]`.
    */
  def instance(dynamoDbClient: DynamoDBClient, logging: Logging): DynamoDb =
    new DynamoDb {

      override def query[A](
          queryRequest: QueryRequest
      )(using deserializer: DynamoDBDeserialiser[A]): Iterator[Either[DynamoDbError, A]] =
        paginate(queryRequest)(req =>
          Try(sendQueryRequest(req)) match {
            case Success(response) => Right(response)
            case Failure(ex)       => Left(DynamoDbError(s"Failed to execute query $req : $ex"))
          }
        )(
          response => response.items.asScala,
          response => Option(response.lastEvaluatedKey).filterNot(_.isEmpty),
          (req, key) => req.copy(x => x.exclusiveStartKey(key))
        ).map(_.flatMap(deserializer.deserialise))

      private def sendQueryRequest(queryRequest: QueryRequest): QueryResponse = {
        logging.info(s"Starting query: $queryRequest")
        dynamoDbClient.query(queryRequest)
      }

      override def scan[A](
          scanRequest: ScanRequest
      )(using deserializer: DynamoDBDeserialiser[A]): Iterator[Either[DynamoDbError, A]] =
        paginate(scanRequest)(req =>
          Try(sendScanRequest(req)) match {
            case Success(response) => Right(response)
            case Failure(ex)       => Left(DynamoDbError(s"Failed to execute scan $req : $ex"))
          }
        )(
          response => response.items.asScala,
          response => Option(response.lastEvaluatedKey).filterNot(_.isEmpty),
          (req, key) => req.copy(x => x.exclusiveStartKey(key))
        ).map(_.flatMap(deserializer.deserialise))

      private def sendScanRequest(scanRequest: ScanRequest): ScanResponse = {
        logging.info(s"Starting scan: $scanRequest")
        dynamoDbClient.scan(scanRequest)
      }

      /** Lazily walks a paginated DynamoDB request/response cycle, yielding one `Right`-wrapped item at a time
        * until either the pages are exhausted or a page-fetch fails, in which case a single trailing `Left` is
        * yielded and iteration stops - mirroring the original `ZStream`'s fail-fast behaviour. `sendRequest`
        * performs one page's I/O; `itemsOf`/`nextKeyOf` extract the page's items and continuation key from the
        * response; `withStartKey` builds the next page's request. Logs each batch's item count, matching the
        * original behaviour.
        */
      private def paginate[Req, Resp](
          initialRequest: Req
      )(
          sendRequest: Req => Either[DynamoDbError, Resp]
      )(
          itemsOf: Resp => Iterable[util.Map[String, AttributeValue]],
          nextKeyOf: Resp => Option[util.Map[String, AttributeValue]],
          withStartKey: (Req, util.Map[String, AttributeValue]) => Req
      ): Iterator[Either[DynamoDbError, util.Map[String, AttributeValue]]] = {
        def pages(request: Option[Req]): Iterator[Either[DynamoDbError, util.Map[String, AttributeValue]]] =
          request match {
            case None      => Iterator.empty
            case Some(req) =>
              sendRequest(req) match {
                case Left(error)     => Iterator.single(Left(error))
                case Right(response) =>
                  val items = itemsOf(response)
                  logging.info(s"Received query results for batch with ${items.size} items")
                  val nextRequest = nextKeyOf(response).map(withStartKey(req, _))
                  items.iterator.map(Right(_)) ++ pages(nextRequest)
              }
          }
        pages(Some(initialRequest))
      }

      override def update[A, B](table: String, key: A, value: B)(using
          keySerializer: DynamoDBSerialiser[A],
          valueSerializer: DynamoDBUpdateSerialiser[B]
      ): Either[DynamoDbError, Unit] =
        Try(
          dynamoDbClient.updateItem(
            UpdateItemRequest.builder
              .tableName(table)
              .key(keySerializer.serialise(key))
              .attributeUpdates(valueSerializer.serialise(value))
              .build()
          )
        ) match {
          case Success(_)  => Right(())
          case Failure(ex) => Left(DynamoDbError(s"Failed to write value '$value' to '$table': $ex"))
        }

      override def create[A](table: String, keyName: String, value: A)(using
          valueSerializer: DynamoDBSerialiser[A]
      ): Either[DynamoDbError, Unit] =
        Try(
          dynamoDbClient.createItem(
            PutItemRequest.builder.tableName(table).item(valueSerializer.serialise(value)).build(),
            keyName
          )
        ) match {
          case Success(_)  => Right(())
          case Failure(ex) => Left(DynamoDbError(s"Failed to write value '$value' to '$table': $ex", Some(ex)))
        }
    }

  val impl: ZLayer[DynamoDBClient with Logging, Nothing, DynamoDb] =
    ZLayer {
      for {
        dynamoDbClient <- ZIO.service[DynamoDBClient]
        logging <- ZIO.service[Logging]
      } yield instance(dynamoDbClient, logging)
    }
}
