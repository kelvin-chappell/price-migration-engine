package pricemigrationengine.services

import pricemigrationengine.TestLogging
import pricemigrationengine.model.ZuoraConfig
import sttp.client4.Response
import sttp.client4.testing.BackendStub
import sttp.model.StatusCode

import java.time.LocalDate

class ZuoraLiveTest extends munit.FunSuite {

  private val config = ZuoraConfig("https://zuora-api-host", "client-id", "client-secret")

  test("makeURI is pure: parses an already-escaped url string without re-escaping it") {
    val uri = ZuoraLive.makeURI("https://zuora-api-host/v1/catalog/products?page=1")
    assertEquals(uri.toString, "https://zuora-api-host/v1/catalog/products?page=1")
  }

  test("buildTokenRequest is pure: posts client-credentials to the oauth/token endpoint") {
    val request = ZuoraLive.buildTokenRequest(config)
    assertEquals(request.uri.toString, "https://zuora-api-host/oauth/token")
    assertEquals(request.headers.find(_.name == "Content-Type").map(_.value), Some("application/x-www-form-urlencoded"))
  }

  test("parseAccessToken is pure: extracts the access_token field") {
    assertEquals(ZuoraLive.parseAccessToken("""{"access_token": "abc123"}"""), "abc123")
  }

  test("buildGetRequest is pure: builds a versioned GET request with query params") {
    val request = ZuoraLive.buildGetRequest(config, "catalog/products", Map("page" -> "2"))
    assertEquals(request.uri.toString, "https://zuora-api-host/v1/catalog/products?page=2")
  }

  test("buildPostRequest is pure: builds a versioned POST request with a JSON body") {
    val request = ZuoraLive.buildPostRequest(config, "async/orders", """{"foo":"bar"}""")
    assertEquals(request.uri.toString, "https://zuora-api-host/v1/async/orders")
    assertEquals(request.headers.find(_.name == "Content-Type").map(_.value), Some("application/json"))
  }

  test("describeFailedResponse is pure: includes method, uri, status and body") {
    val request = ZuoraLive.buildGetRequest(config, "subscriptions/S-001", Map.empty)
    val response = Response("boom", StatusCode.InternalServerError, "Internal Server Error", Nil, Nil, request)
    val description = ZuoraLive.describeFailedResponse("test-code", request, response)

    assert(description.contains("test-code"))
    assert(description.contains("subscriptions/S-001"))
    assert(description.contains("500"))
    assert(description.contains("boom"))
  }

  test("instance.fetchSubscription fetches the token once and returns the parsed subscription") {
    val subscriptionJson =
      """{
        |"subscriptionNumber":"S-001","id":"id-1","version":1,
        |"customerAcceptanceDate":"2020-01-01","contractEffectiveDate":"2020-01-01","subscriptionStartDate":"2020-01-01",
        |"ratePlans":[],"accountNumber":"A-001","accountId":"account-id","status":"Active",
        |"termStartDate":"2020-01-01","termEndDate":"2021-01-01","autoRenew":true
        |}""".stripMargin

    var tokenRequests = 0
    val backend = BackendStub.synchronous
      .whenRequestMatches { req =>
        val isTokenRequest = req.uri.toString.contains("oauth/token")
        if (isTokenRequest) tokenRequests += 1
        isTokenRequest
      }
      .thenRespondAdjust("""{"access_token":"tok-1"}""")
      .whenRequestMatches(req => req.uri.toString.contains("subscriptions/S-001"))
      .thenRespondAdjust(subscriptionJson)

    val zuora = ZuoraLive.instance(config, TestLogging.instance, backend)
    val subscription = zuora.fetchSubscription("S-001")

    assertEquals(subscription.map(_.subscriptionNumber), Right("S-001"))
    assertEquals(tokenRequests, 1)
  }

  test("instance.fetchSubscription returns a Left with contextual info when the request fails") {
    val backend = BackendStub.synchronous
      .whenRequestMatches(req => req.uri.toString.contains("oauth/token"))
      .thenRespondAdjust("""{"access_token":"tok-1"}""")
      .whenRequestMatches(req => req.uri.toString.contains("subscriptions/S-001"))
      .thenRespondServerError()

    val zuora = ZuoraLive.instance(config, TestLogging.instance, backend)
    val result = zuora.fetchSubscription("S-001")

    result match {
      case Left(failure) => assert(failure.reason.contains("Subscription S-001"))
      case Right(_)      => fail("expected a Left")
    }
  }
}
