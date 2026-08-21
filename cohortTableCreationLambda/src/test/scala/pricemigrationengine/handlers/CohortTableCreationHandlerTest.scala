package pricemigrationengine.handlers

import pricemigrationengine.model._
import pricemigrationengine.services._
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse
import zio.{IO, Runtime, Unsafe, ZIO, ZLayer}

import java.time.LocalDate

/** Smoke test for `CohortTableCreationHandler`, added as part of its migration to Scala 3 (this module doesn't
  * pull in zio-test, to avoid mixing the `_3` and `_2.13` builds of zio on one classpath - see the comment on
  * `core`/`coreScala3` in build.sbt), so this uses plain munit + a hand-run `zio.Runtime` instead.
  */
class CohortTableCreationHandlerTest extends munit.FunSuite {

  private val cohortSpec: CohortSpec = CohortSpec("cohortName", LocalDate.of(2022, 1, 1))

  private def unsafeRun[E, A](zio: ZIO[Any, E, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(zio).getOrThrowFiberFailure())

  private val stubLogging: ZLayer[Any, Nothing, Logging] =
    ZLayer.succeed(new Logging {
      def info(s: String): Unit = ()
      def error(s: String): Unit = ()
    })

  private def stubCohortTableDdl(response: Option[CreateTableResponse]): ZLayer[Any, Nothing, CohortTableDdl] =
    ZLayer.succeed(new CohortTableDdl {
      def createTable(cohortSpec: CohortSpec): Either[CohortTableCreateFailure, Option[CreateTableResponse]] =
        Right(response)
    })

  test("CohortTableCreationHandler.main reports completion when the table is created") {
    val result = unsafeRun(
      CohortTableCreationHandler
        .main(cohortSpec)
        .provideLayer(stubLogging ++ stubCohortTableDdl(Some(CreateTableResponse.builder().build())))
    )

    assertEquals(result, HandlerOutput(isComplete = true))
  }

  test("CohortTableCreationHandler.main reports completion when the table already exists") {
    val result = unsafeRun(
      CohortTableCreationHandler
        .main(cohortSpec)
        .provideLayer(stubLogging ++ stubCohortTableDdl(None))
    )

    assertEquals(result, HandlerOutput(isComplete = true))
  }
}
