package pricemigrationengine.handlers

import pricemigrationengine.model.CohortTableFilter.{AmendmentComplete, AmendmentWrittenToSalesforce}
import pricemigrationengine.model._
import pricemigrationengine.services._
import zio.{Clock, IO, Runtime, UIO, Unsafe, ZIO, ZLayer}

import java.time.{Instant, LocalDate}
import scala.collection.mutable.ArrayBuffer

/** Smoke test for `SalesforceAmendmentUpdateHandler`, added as part of its migration to Scala 3 (this module
  * doesn't pull in zio-test, to avoid mixing the `_3` and `_2.13` builds of zio on one classpath - see the
  * comment on `core`/`coreScala3` in build.sbt), so this uses plain munit + a hand-run `zio.Runtime` instead.
  */
class SalesforceAmendmentUpdateHandlerTest extends munit.FunSuite {

  private val cohortSpec: CohortSpec = CohortSpec("cohortName", LocalDate.of(2022, 1, 1))
  private val currentTime: Instant = Instant.parse("2020-05-21T15:16:37Z")

  private def unsafeRun[E, A](zio: ZIO[Any, E, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(zio).getOrThrowFiberFailure())

  private def stubClock(fixedInstant: Instant): Clock = new Clock {
    export Clock.ClockLive.{instant as _, *}
    override def instant(implicit trace: zio.Trace): UIO[Instant] = ZIO.succeed(fixedInstant)
  }

  private val stubLogging: ZLayer[Any, Nothing, Logging] =
    ZLayer.succeed(new Logging {
      def info(s: String): Unit = ()
      def error(s: String): Unit = ()
    })

  private def stubCohortTable(updated: ArrayBuffer[CohortItem], item: CohortItem): ZLayer[Any, Nothing, CohortTable] =
    ZLayer.succeed(new CohortTable {
      def fetch(
          filter: CohortTableFilter,
          latestAmendmentEffectiveDateInclusive: Option[LocalDate]
      ): Iterator[Either[CohortFetchFailure, CohortItem]] = {
        assertEquals(filter, AmendmentComplete)
        Iterator(Right(item))
      }
      def create(cohortItem: CohortItem): Either[Failure, Unit] = ???
      def update(result: CohortItem): Either[CohortUpdateFailure, Unit] = {
        updated.addOne(result)
        Right(())
      }
      def fetchAll(): Iterator[Either[CohortFetchFailure, CohortItem]] = ???
    })

  private def stubSalesforceClient(updated: ArrayBuffer[SalesforcePriceRise]): ZLayer[Any, Nothing, SalesforceClient] =
    ZLayer.succeed(new SalesforceClient {
      def getSubscriptionByName(subscriptionName: String): Either[SalesforceClientFailure, SalesforceSubscription] =
        ???
      def getContact(contactId: String): Either[SalesforceClientFailure, SalesforceContact] = ???
      def createPriceRise(
          priceRise: SalesforcePriceRise
      ): Either[SalesforceClientFailure, SalesforcePriceRiseCreationResponse] = ???
      def updatePriceRise(
          priceRiseId: String,
          priceRise: SalesforcePriceRise
      ): Either[SalesforceClientFailure, Unit] = {
        updated.addOne(priceRise)
        Right(())
      }
      def getPriceRise(priceRiseId: String): Either[SalesforceClientFailure, SalesforcePriceRise] =
        Right(
          SalesforcePriceRise(
            Migration_Name__c = None,
            Migration_Status__c = None,
            Cancellation_Reason__c = None
          )
        )
    })

  test("SalesforceAmendmentUpdateHandler.main updates Salesforce for every completed amendment") {
    val cohortItem = CohortItem(
      subscriptionName = "subscription-id",
      processingStage = AmendmentComplete,
      salesforcePriceRiseId = Some("price-rise-id"),
      newSubscriptionId = Some("new-subscription-id")
    )
    val updatedResultsWrittenToCohortTable = ArrayBuffer[CohortItem]()
    val updatedPriceRises = ArrayBuffer[SalesforcePriceRise]()

    val result = unsafeRun(
      SalesforceAmendmentUpdateHandler
        .main(cohortSpec)
        .withClock(stubClock(currentTime))
        .provideLayer(
          stubLogging ++ stubCohortTable(updatedResultsWrittenToCohortTable, cohortItem) ++ stubSalesforceClient(
            updatedPriceRises
          )
        )
    )

    assertEquals(result, HandlerOutput(isComplete = true))
    assertEquals(updatedPriceRises.size, 1)
    assertEquals(updatedPriceRises(0).Amended_Zuora_Subscription_Id__c, Some("new-subscription-id"))
    assertEquals(updatedResultsWrittenToCohortTable.size, 1)
    assertEquals(updatedResultsWrittenToCohortTable(0).processingStage, AmendmentWrittenToSalesforce)
    assertEquals(updatedResultsWrittenToCohortTable(0).whenAmendmentWrittenToSalesforce, Some(currentTime))
  }
}
