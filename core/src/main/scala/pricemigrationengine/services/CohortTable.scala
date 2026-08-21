package pricemigrationengine.services

import java.time.LocalDate

import pricemigrationengine.model._
import zio.stream.ZStream
import zio.{IO, ZIO}

case class CohortTableKey(subscriptionNumber: String)

trait CohortTable {

  /** Lazily paginates through the Cohort table - see `DynamoDb.query`/`.scan` for the pagination/fail-fast
    * semantics this preserves.
    */
  def fetch(
      filter: CohortTableFilter,
      latestAmendmentEffectiveDateInclusive: Option[LocalDate]
  ): Iterator[Either[CohortFetchFailure, CohortItem]]

  def fetchAll(): Iterator[Either[CohortFetchFailure, CohortItem]]

  def create(cohortItem: CohortItem): Either[Failure, Unit]

  def update(cohortItem: CohortItem): Either[CohortUpdateFailure, Unit]
}

object CohortTable {

  /** ZIO-facing compatibility shim for not-yet-converted callers. Wraps the plain, lazy
    * `Iterator[Either[CohortFetchFailure, CohortItem]]` in a `ZStream`, failing the stream as soon as a `Left`
    * is encountered - matching the original `ZStream`'s error-channel semantics.
    */
  def fetch(
      filter: CohortTableFilter,
      latestAmendmentEffectiveDateInclusive: Option[LocalDate]
  ): ZStream[CohortTable, CohortFetchFailure, CohortItem] =
    ZStream.serviceWithStream(env => streamOf(env.fetch(filter, latestAmendmentEffectiveDateInclusive)))

  def fetchAll(): ZStream[CohortTable, CohortFetchFailure, CohortItem] =
    ZStream.serviceWithStream(env => streamOf(env.fetchAll()))

  def create(subscription: CohortItem): ZIO[CohortTable, Failure, Unit] =
    ZIO.environmentWithZIO(env => ZIO.fromEither(env.get.create(subscription)))

  def update(cohortItem: CohortItem): ZIO[CohortTable, CohortUpdateFailure, Unit] =
    ZIO.environmentWithZIO(env => ZIO.fromEither(env.get.update(cohortItem)))

  private def streamOf(
      iterator: => Iterator[Either[CohortFetchFailure, CohortItem]]
  ): ZStream[Any, CohortFetchFailure, CohortItem] =
    ZStream
      .fromIterator(iterator)
      .mapError(ex => CohortFetchFailure(ex.getMessage))
      .flatMap {
        case Right(value) => ZStream.succeed(value)
        case Left(error)  => ZStream.fail(error)
      }
}
