package pricemigrationengine.handlers

import pricemigrationengine.model._
import pricemigrationengine.services._
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse
import zio.{IO, Runtime, Unsafe, ZIO, ZLayer}

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Smoke test for `MigrationHandler`, added as part of its migration to Scala 3 (this module doesn't pull in
  * zio-test, to avoid mixing the `_3` and `_2.13` builds of zio on one classpath - see the comment on
  * `core`/`coreScala3` in build.sbt), so this uses plain munit + a hand-run `zio.Runtime` instead.
  */
class MigrationHandlerTest extends munit.FunSuite {

  private val cohortSpecs: Set[CohortSpec] = Set(
    CohortSpec("cohortName1", LocalDate.of(2022, 1, 1)),
    CohortSpec("cohortName2", LocalDate.of(2022, 1, 1))
  )

  private def unsafeRun[E, A](zio: ZIO[Any, E, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(zio).getOrThrowFiberFailure())

  private val stubLogging: ZLayer[Any, Nothing, Logging] =
    ZLayer.succeed(new Logging {
      def info(s: String): Unit = ()
      def error(s: String): Unit = ()
    })

  private def stubCohortSpecTable(specs: Set[CohortSpec]): ZLayer[Any, Nothing, CohortSpecTable] =
    ZLayer.succeed(new CohortSpecTable {
      val fetchAll: IO[Failure, Set[CohortSpec]] = ZIO.succeed(specs)
      def update(spec: CohortSpec): ZIO[Any, CohortSpecUpdateFailure, Unit] = ZIO.unit
    })

  private def stubCohortStateMachine(startedCount: AtomicInteger): ZLayer[Any, Nothing, CohortStateMachine] =
    ZLayer.succeed(new CohortStateMachine {
      def startExecution(spec: CohortSpec): IO[CohortStateMachineFailure, StartExecutionResponse] =
        ZIO.succeed {
          startedCount.incrementAndGet()
          StartExecutionResponse.builder().build()
        }
    })

  test("MigrationHandler.migrateActiveCohorts starts an execution for every active cohort") {
    val startedCount = new AtomicInteger(0)

    unsafeRun(
      MigrationHandler.migrateActiveCohorts
        .provideLayer(stubLogging ++ stubCohortSpecTable(cohortSpecs) ++ stubCohortStateMachine(startedCount))
    )

    assertEquals(startedCount.get(), cohortSpecs.size)
  }

  test("MigrationHandler.migrateActiveCohorts does nothing when there are no active cohorts") {
    val startedCount = new AtomicInteger(0)

    unsafeRun(
      MigrationHandler.migrateActiveCohorts
        .provideLayer(stubLogging ++ stubCohortSpecTable(Set.empty) ++ stubCohortStateMachine(startedCount))
    )

    assertEquals(startedCount.get(), 0)
  }
}
