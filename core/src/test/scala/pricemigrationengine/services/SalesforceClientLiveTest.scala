package pricemigrationengine.services

import java.time.LocalDate
import pricemigrationengine.TestLogging
import pricemigrationengine.model.{SalesforceConfig, SalesforcePriceRise, SalesforceSubscription, ZuoraSubscriptionId}
import sttp.client4.testing.BackendStub
import upickle.default._

class SalesforceClientLiveTest extends munit.FunSuite {

  private val config = SalesforceConfig(
    authUrl = "https://salesforce-auth-host",
    clientId = "client-id",
    clientSecret = "client-secret",
    userName = "user",
    password = "pass",
    token = "token"
  )

  test("makeURI is pure: parses an already-escaped url string without re-escaping it") {
    val uri = SalesforceClientLive.makeURI("https://salesforce-auth-host/services/oauth2/token")
    assertEquals(uri.toString, "https://salesforce-auth-host/services/oauth2/token")
  }

  test("buildAuthRequest is pure: posts a password-grant request to the oauth2/token endpoint") {
    val request = SalesforceClientLive.buildAuthRequest(config)
    assertEquals(request.uri.toString, "https://salesforce-auth-host/services/oauth2/token")
    assertEquals(
      request.headers.find(_.name == "Content-Type").map(_.value),
      Some("application/x-www-form-urlencoded")
    )
  }

  test("instance.getSubscriptionByName authenticates once and returns the parsed subscription") {
    var authRequests = 0
    val backend = BackendStub.synchronous
      .whenRequestMatches { req =>
        val isAuthRequest = req.uri.toString.contains("oauth2/token")
        if (isAuthRequest) authRequests += 1
        isAuthRequest
      }
      .thenRespondAdjust("""{"access_token":"tok-1","instance_url":"https://salesforce-instance-host"}""")
      .whenRequestMatches(req => req.uri.toString.contains("SF_Subscription__c/Name/Sub-001"))
      .thenRespondAdjust(
        """{"Id":"sub-id","Name":"Sub-001","Buyer__c":"buyer-id","Status__c":"Active","Product_Type__c":"Membership"}"""
      )

    val client = SalesforceClientLive.instance(config, TestLogging.instance, backend)
    val subscription = client.getSubscriptionByName("Sub-001")

    assertEquals(subscription.map(_.Name), Right("Sub-001"))
    assertEquals(authRequests, 1)
  }

  test("instance.getSubscriptionByName returns a Left with contextual info when the request fails") {
    val backend = BackendStub.synchronous
      .whenRequestMatches(req => req.uri.toString.contains("oauth2/token"))
      .thenRespondAdjust("""{"access_token":"tok-1","instance_url":"https://salesforce-instance-host"}""")
      .whenRequestMatches(req => req.uri.toString.contains("SF_Subscription__c/Name/Sub-001"))
      .thenRespondServerError()

    val client = SalesforceClientLive.instance(config, TestLogging.instance, backend)
    val result = client.getSubscriptionByName("Sub-001")

    result match {
      case Left(failure) => assert(failure.reason.contains("4d8d6c12"))
      case Right(_)      => fail("expected a Left")
    }
  }

  test("SalesforceClientLive should serialise SalesforcePriceRise with all fields") {
    assertEquals(
      SalesforceClientLive.serialisePriceRise(
        SalesforcePriceRise(
          Name = Some("name"),
          Buyer__c = Some("buyer"),
          Current_Price_Today__c = Some(BigDecimal(1.23)),
          Guardian_Weekly_New_Price__c = Some(BigDecimal(1.99)),
          Price_Rise_Date__c = Some(LocalDate.of(2020, 1, 1)),
          SF_Subscription__c = Some("subscriptionId"),
          Date_Letter_Sent__c = Some(LocalDate.of(2020, 1, 2)),
          Migration_Name__c = Some("cohortName"),
          Migration_Status__c = Some("EstimationComplete"),
          Cancellation_Reason__c = None
        )
      ),
      """{
        |  "Name": "name",
        |  "Buyer__c": "buyer",
        |  "Current_Price_Today__c": 1.23,
        |  "Guardian_Weekly_New_Price__c": 1.99,
        |  "Price_Rise_Date__c": "2020-01-01",
        |  "SF_Subscription__c": "subscriptionId",
        |  "Date_Letter_Sent__c": "2020-01-02",
        |  "Migration_Name__c": "cohortName",
        |  "Migration_Status__c": "EstimationComplete",
        |  "Cancellation_Reason__c": null
        |}""".stripMargin
    )
  }
  test("SalesforceClientLive should serialise SalesforcePriceRise with fields missing") {
    assertEquals(
      SalesforceClientLive.serialisePriceRise(
        SalesforcePriceRise(
          Date_Letter_Sent__c = Some(LocalDate.of(2020, 1, 2)),
          Migration_Name__c = Some("cohortName"),
          Migration_Status__c = Some("EstimationComplete"),
          Cancellation_Reason__c = Some("Error 1")
        )
      ),
      """{
        |  "Date_Letter_Sent__c": "2020-01-02",
        |  "Migration_Name__c": "cohortName",
        |  "Migration_Status__c": "EstimationComplete",
        |  "Cancellation_Reason__c": "Error 1"
        |}""".stripMargin
    )
  }
  test("SalesforceClientLive should deserialise correctly the JSON object from Salesforce") {
    implicit val bigDecimalRW: ReadWriter[BigDecimal] =
      readwriter[ujson.Value].bimap[BigDecimal](
        bd => ujson.Num(bd.toDouble), // write
        js => js.num // read as number
      )
    implicit val localDateRW: ReadWriter[LocalDate] =
      readwriter[String].bimap[LocalDate](_.toString, LocalDate.parse)
    implicit val salesforcePriceRiseRW: ReadWriter[SalesforcePriceRise] = macroRW

    val rawJSON = """{
    |  "Migration_Name__c": "cohortName",
    |  "Migration_Status__c": "EstimationComplete",
    |  "Cancellation_Reason__c": "Error 1",
    |  "Current_Price_Today__c":311.88,
    |  "Guardian_Weekly_New_Price__c": 335.88
    |}""".stripMargin

    val priceRiseObject = read[SalesforcePriceRise](rawJSON)
    assertEquals(
      priceRiseObject.Guardian_Weekly_New_Price__c,
      Some(BigDecimal(335.88))
    )
  }
}
