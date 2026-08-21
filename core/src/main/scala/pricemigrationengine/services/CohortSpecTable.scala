package pricemigrationengine.services

import pricemigrationengine.model.{CohortSpec, CohortSpecUpdateFailure, Failure}
import zio.ZIO

/** For accessing the specifications of each cohort.
  */
trait CohortSpecTable {
  def fetchAll(): Either[Failure, Set[CohortSpec]]
  def update(spec: CohortSpec): Either[CohortSpecUpdateFailure, Unit]
}

object CohortSpecTable {

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  val fetchAll: ZIO[CohortSpecTable, Failure, Set[CohortSpec]] =
    ZIO.environmentWithZIO(env => ZIO.fromEither(env.get.fetchAll()))

  def update(spec: CohortSpec): ZIO[CohortSpecTable, CohortSpecUpdateFailure, Unit] =
    ZIO.environmentWithZIO(env => ZIO.fromEither(env.get.update(spec)))
}
