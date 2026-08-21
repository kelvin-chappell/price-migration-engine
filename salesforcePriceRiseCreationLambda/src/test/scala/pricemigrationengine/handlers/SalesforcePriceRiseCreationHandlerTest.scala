package pricemigrationengine.handlers

import pricemigrationengine.model.CohortTableFilter.{EstimationComplete, SalesforcePriceRiseCreationComplete}
import pricemigrationengine.model._
import pricemigrationengine.services._
import zio.stream.ZStream
import zio.{Clock, IO, Runtime, UIO, Unsafe, ZIO, ZLayer}

import java.time.{Instant, LocalDate}
import scala.collection.mutable.ArrayBuffer

/** Smoke test for `SalesforcePriceRiseCreationHandler`, added as part of its migration to Scala 3 (this module
  * doesn't pull in zio-test, to avoid mixing the `_3` and `_2.13` builds of zio on one classpath - see the
  * comment on `core`/`coreScala3` in build.sbt), so this uses plain munit + a hand-run `zio.Runtime` instead.
  */
class SalesforcePriceRiseCreationHandlerTest extends munit.FunSuite {

  private val cohortSpec: CohortSpec = CohortSpec("cohortName", LocalDate.of(2022, 1, 1))
  private val currentTime: Instant = Instant.parse("2020-05-21T15:16:37Z")

  private val subscription = SalesforceSubscription(
    Id = "subscription-sf-id",
    Name = "subscription-name",
    Buyer__c = "buyer-id",
    Status__c = "Active",
    Product_Type__c = Some("Membership")
  )

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
      ): ZStream[Any, CohortFetchFailure, CohortItem] = {
        assertEquals(filter, EstimationComplete)
        ZStream(item)
      }
      def create(cohortItem: CohortItem): ZIO[Any, Failure, Unit] = ???
      def update(result: CohortItem): ZIO[Any, CohortUpdateFailure, Unit] =
        ZIO.succeed(updated.addOne(result)).unit
      def fetchAll(): ZStream[Any, CohortFetchFailure, CohortItem] = ???
    })

  private def stubSalesforceClient(created: ArrayBuffer[SalesforcePriceRise]): ZLayer[Any, Nothing, SalesforceClient] =
    ZLayer.succeed(new SalesforceClient {
      def getSubscriptionByName(subscriptionName: String): Either[SalesforceClientFailure, SalesforceSubscription] =
        Right(subscription)
      def getContact(contactId: String): Either[SalesforceClientFailure, SalesforceContact] = ???
      def createPriceRise(
          priceRise: SalesforcePriceRise
      ): Either[SalesforceClientFailure, SalesforcePriceRiseCreationResponse] = {
        created.addOne(priceRise)
        Right(SalesforcePriceRiseCreationResponse("new-price-rise-id"))
      }
      def updatePriceRise(priceRiseId: String, priceRise: SalesforcePriceRise): Either[SalesforceClientFailure, Unit] =
        ???
      def getPriceRise(priceRiseId: String): Either[SalesforceClientFailure, SalesforcePriceRise] = ???
    })

  test("SalesforcePriceRiseCreationHandler.main creates a Salesforce price rise for every estimated cohort item") {
    val cohortItem = CohortItem(
      subscriptionName = "subscription-id",
      processingStage = EstimationComplete,
      oldPrice = Some(BigDecimal(120)),
      commsPrice = Some(BigDecimal(140)),
      amendmentEffectiveDate = Some(LocalDate.of(2022, 5, 1))
    )
    val updatedResultsWrittenToCohortTable = ArrayBuffer[CohortItem]()
    val createdPriceRises = ArrayBuffer[SalesforcePriceRise]()

    val result = unsafeRun(
      SalesforcePriceRiseCreationHandler
        .main(cohortSpec)
        .withClock(stubClock(currentTime))
        .provideLayer(
          stubLogging ++ stubCohortTable(updatedResultsWrittenToCohortTable, cohortItem) ++ stubSalesforceClient(
            createdPriceRises
          )
        )
    )

    assertEquals(result, HandlerOutput(isComplete = true))
    assertEquals(createdPriceRises.size, 1)
    assertEquals(createdPriceRises(0).SF_Subscription__c, Some(subscription.Id))
    assertEquals(updatedResultsWrittenToCohortTable.size, 1)
    assertEquals(updatedResultsWrittenToCohortTable(0).processingStage, SalesforcePriceRiseCreationComplete)
    assertEquals(updatedResultsWrittenToCohortTable(0).salesforcePriceRiseId, Some("new-price-rise-id"))
    assertEquals(updatedResultsWrittenToCohortTable(0).whenSfShowEstimate, Some(currentTime))
  }
}
