package pricemigrationengine.services

import pricemigrationengine.model.{CohortSpec, CohortTableCreateFailure}
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse
import zio.ZIO

/** Service to run DDL statements on CohortTables. Creation and dropping, etc.
  */
trait CohortTableDdl {

  /** Create a table for the given CohortSpec if it doesn't already exist. Otherwise do nothing.
    */
  def createTable(cohortSpec: CohortSpec): Either[CohortTableCreateFailure, Option[CreateTableResponse]]
}

object CohortTableDdl {

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  def createTable(cohortSpec: CohortSpec): ZIO[CohortTableDdl, CohortTableCreateFailure, Option[CreateTableResponse]] =
    ZIO.environmentWithZIO(env => ZIO.fromEither(env.get.createTable(cohortSpec)))
}
