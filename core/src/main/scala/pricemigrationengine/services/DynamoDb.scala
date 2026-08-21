package pricemigrationengine.services

import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, AttributeValueUpdate, QueryRequest, ScanRequest}
import zio.stream.ZStream
import zio.{IO, ZIO}

case class DynamoDbError(message: String, cause: Option[Throwable] = None)

trait DynamoDBSerialiser[A] { def serialise(value: A): java.util.Map[String, AttributeValue] }
trait DynamoDBUpdateSerialiser[A] { def serialise(value: A): java.util.Map[String, AttributeValueUpdate] }
trait DynamoDBDeserialiser[A] {
  def deserialise(value: java.util.Map[String, AttributeValue]): Either[DynamoDbError, A]
}

trait DynamoDb {

  /** Lazily paginates through DynamoDB, fetching the next page only as the returned iterator is consumed -
    * preserving the original `ZStream.unfoldZIO`'s lazy-pagination behaviour without a streaming library. The
    * iterator yields a `Left` (surfacing a page-fetch or per-item deserialisation failure) at the point
    * consumption reaches the failing element, then stops (matching the original `ZStream`'s
    * fail-fast-on-first-error behaviour).
    */
  def query[A](
      query: QueryRequest
  )(using DynamoDBDeserialiser[A]): Iterator[Either[DynamoDbError, A]]

  def scan[A](
      query: ScanRequest
  )(using DynamoDBDeserialiser[A]): Iterator[Either[DynamoDbError, A]]

  def update[A, B](table: String, key: A, value: B)(using
      DynamoDBSerialiser[A],
      DynamoDBUpdateSerialiser[B]
  ): Either[DynamoDbError, Unit]

  def create[A](table: String, keyName: String, value: A)(using
      DynamoDBSerialiser[A]
  ): Either[DynamoDbError, Unit]
}

object DynamoDb {

  /** ZIO-facing compatibility shim for not-yet-converted callers. Wraps the plain, lazy
    * `Iterator[Either[DynamoDbError, A]]` in a `ZStream`, failing the stream as soon as a `Left` is
    * encountered - matching the original `ZStream`'s error-channel semantics.
    */
  def query[A](
      query: QueryRequest
  )(using DynamoDBDeserialiser[A]): ZIO[DynamoDb, Nothing, ZStream[Any, DynamoDbError, A]] =
    ZIO.environmentWith(env => streamOf(env.get.query(query)))

  def scan[A](
      query: ScanRequest
  )(using DynamoDBDeserialiser[A]): ZIO[DynamoDb, Nothing, ZStream[Any, DynamoDbError, A]] =
    ZIO.environmentWith(env => streamOf(env.get.scan(query)))

  def update[A, B](table: String, key: A, value: B)(using
      DynamoDBSerialiser[A],
      DynamoDBUpdateSerialiser[B]
  ): ZIO[DynamoDb, DynamoDbError, Unit] =
    ZIO.environmentWithZIO(env => ZIO.fromEither(env.get.update(table, key, value)))

  def create[A](table: String, keyName: String, value: A)(using
      DynamoDBSerialiser[A]
  ): ZIO[DynamoDb, DynamoDbError, Unit] =
    ZIO.environmentWithZIO(env => ZIO.fromEither(env.get.create(table, keyName, value)))

  private def streamOf[A](iterator: => Iterator[Either[DynamoDbError, A]]): ZStream[Any, DynamoDbError, A] =
    ZStream
      .fromIterator(iterator)
      .mapError(ex => DynamoDbError(ex.getMessage))
      .flatMap {
        case Right(value) => ZStream.succeed(value)
        case Left(error)  => ZStream.fail(error)
      }
}
