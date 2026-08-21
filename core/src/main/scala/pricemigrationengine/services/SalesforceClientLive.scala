package pricemigrationengine.services

import java.time.LocalDate
import pricemigrationengine.model.{
  SalesforceAddress,
  SalesforceClientFailure,
  SalesforceConfig,
  SalesforceContact,
  SalesforcePriceRise,
  SalesforceSubscription
}

import upickle.default._
import zio.{ZIO, ZLayer}
import sttp.client4._
import sttp.client4.httpclient.HttpClientSyncBackend
import sttp.model.Uri

import scala.concurrent.duration._

object SalesforceClientLive {

  private case class SalesforceAuthDetails(access_token: String, instance_url: String)

  implicit private val localDateRW: ReadWriter[LocalDate] =
    readwriter[String].bimap[LocalDate](_.toString, LocalDate.parse)
  implicit private val salesforceAuthDetailsRW: ReadWriter[SalesforceAuthDetails] = macroRW
  implicit private val salesforceSubscriptionRW: ReadWriter[SalesforceSubscription] = macroRW
  implicit private val salesforcePriceRiseRW: ReadWriter[SalesforcePriceRise] = macroRW
  implicit private val salesforcePriceIdRiseRW: ReadWriter[SalesforcePriceRiseCreationResponse] = macroRW
  implicit private val salesforceAddressRW: ReadWriter[SalesforceAddress] = macroRW
  implicit private val salesforceContactRW: ReadWriter[SalesforceContact] = macroRW

  // Do not remove this:
  implicit private val bigDecimalRW: ReadWriter[BigDecimal] =
    readwriter[ujson.Value].bimap[BigDecimal](
      bd => ujson.Num(bd.toDouble), // write
      js => js.num // read as number
    )

  private val requestTimeout: Duration = 30.seconds

  private val salesforceApiPathPrefixToVersion = "services/data/v60.0"

  /** Pure: wraps an already-correctly-escaped URL string in a `Uri`. Uses `unsafeParse` rather than `Uri(string)`
    * because the latter would re-escape an already-valid URL (needed since `.patch`/query-param building
    * requires a `Uri`, not a raw string). No I/O.
    */
  private[services] def makeURI(url: String): Uri = Uri.unsafeParse(url)

  /** Pure: builds the OAuth2 password-grant token request. No I/O. */
  private[services] def buildAuthRequest(config: SalesforceConfig): Request[String] =
    basicRequest
      .post(makeURI(s"${config.authUrl}/services/oauth2/token"))
      .body(
        Map(
          "grant_type" -> "password",
          "client_id" -> config.clientId,
          "client_secret" -> config.clientSecret,
          "username" -> config.userName,
          "password" -> s"${config.password}${config.token}"
        )
      )
      .contentType("application/x-www-form-urlencoded")
      .response(asStringAlways)

  /** Pure: describes a failed Salesforce HTTP response, matching the original error-message format. */
  private[services] def describeFailedResponse(
      errorCode: String,
      request: Request[String],
      response: Response[String]
  ): String =
    s"""
       |(error: $errorCode)
       |Salesforce request failed
       |Method: ${request.method}
       |URI: ${request.uri}
       |Status: ${response.code}
       |Body: ${response.body}
       |""".stripMargin

  /** Effectful: sends the request via the given sync backend, throwing on a transport error or a
    * non-success response (with `describeFailedResponse`'s message).
    */
  private[services] def performRequest(request: Request[String], backend: SyncBackend): Response[String] = {
    val response =
      try backend.send(request)
      catch {
        case ex: Exception =>
          throw new RuntimeException(s"Request for ${request.method} ${request.uri} failed: $ex")
      }
    if (response.code.isSuccess) response
    else throw new RuntimeException(describeFailedResponse("4d8d6c12", request, response))
  }

  private def performRequestAndParseAnswer[A](request: Request[String], backend: SyncBackend, logging: Logging)(implicit
      reader: Reader[A]
  ): A = {
    val body = performRequest(request, backend).body
    logging.info(s"[66412c75] successful response body: $body")
    try read[A](body)
    catch {
      case ex: Exception => throw new RuntimeException(s"[de6f48da] failed to deserialise: $body, error: $ex")
    }
  }

  private def authenticate(config: SalesforceConfig, backend: SyncBackend, logging: Logging): SalesforceAuthDetails = {
    val request = buildAuthRequest(config)
    val authDetails = performRequestAndParseAnswer[SalesforceAuthDetails](request, backend, logging)
    logging.info(
      s"[c6f8f9f7] Authenticated with salesforce using user:${config.userName} and client: ${config.clientId}"
    )
    authDetails
  }

  /** Plain, direct-style implementation. Effectful methods return `Left(SalesforceClientFailure)` on failure
    * rather than throwing - the failure and its context are values, not exceptions.
    *
    * The auth token is fetched once, eagerly, at construction time - matching the previous
    * ZLayer-construction-time behaviour. The per-request `Authorization` header is a redacted placeholder in
    * this codebase (matching `ZuoraLive`'s pre-existing behaviour), not the actual bearer token.
    */
  def instance(
      config: SalesforceConfig,
      logging: Logging,
      backend: SyncBackend = HttpClientSyncBackend()
  ): SalesforceClient = {
    val auth = authenticate(config, backend, logging)

    def get[A: Reader](path: String): A =
      performRequestAndParseAnswer[A](
        basicRequest
          .get(makeURI(s"${auth.instance_url}/$salesforceApiPathPrefixToVersion/$path"))
          .header("Authorization", s"******")
          .contentType("application/json")
          .response(asStringAlways)
          .readTimeout(requestTimeout),
        backend,
        logging
      )

    new SalesforceClient {

      override def getSubscriptionByName(
          subscriptionName: String
      ): Either[SalesforceClientFailure, SalesforceSubscription] =
        try {
          val subscription =
            get[SalesforceSubscription](s"sobjects/SF_Subscription__c/Name/$subscriptionName")
          logging.info(s"[ce8f4177] Successfully loaded subscription ${subscription.Name} from Salesforce")
          Right(subscription)
        } catch {
          case ex: Exception => Left(SalesforceClientFailure(ex.getMessage))
        }

      override def getContact(contactId: String): Either[SalesforceClientFailure, SalesforceContact] =
        try {
          val contact = get[SalesforceContact](s"sobjects/Contact/$contactId")
          logging.info(s"[0309af88] Successfully loaded contact: ${contact.Id}")
          Right(contact)
        } catch {
          case ex: Exception => Left(SalesforceClientFailure(ex.getMessage))
        }

      override def createPriceRise(
          priceRise: SalesforcePriceRise
      ): Either[SalesforceClientFailure, SalesforcePriceRiseCreationResponse] = {
        val request =
          basicRequest
            .post(makeURI(s"${auth.instance_url}/$salesforceApiPathPrefixToVersion/sobjects/Price_Rise__c/"))
            .body(serialisePriceRise(priceRise))
            .header("Authorization", s"******")
            .contentType("application/json")
            .response(asStringAlways)
            .readTimeout(requestTimeout)

        try {
          val created =
            performRequestAndParseAnswer[SalesforcePriceRiseCreationResponse](request, backend, logging)
          logging.info(s"[e3e340a7] Successfully created Price_Rise__c object: ${created.id}")
          Right(created)
        } catch {
          case ex: Exception => Left(SalesforceClientFailure(ex.getMessage))
        }
      }

      override def updatePriceRise(
          priceRiseId: String,
          priceRise: SalesforcePriceRise
      ): Either[SalesforceClientFailure, Unit] = {
        val request =
          basicRequest
            .patch(
              makeURI(s"${auth.instance_url}/$salesforceApiPathPrefixToVersion/sobjects/Price_Rise__c/$priceRiseId")
            )
            .body(serialisePriceRise(priceRise))
            .header("Authorization", s"******")
            .contentType("application/json")
            .response(asStringAlways)
            .readTimeout(requestTimeout)

        try {
          performRequest(request, backend)
          logging.info(s"[bb7d65d1] Successfully updated Price_Rise__c object, priceRiseId: $priceRiseId")
          Right(())
        } catch {
          case ex: Exception =>
            val failure = SalesforceClientFailure(ex.getMessage)
            logging.error(s"[bb7d65d1] Failed to update Price_Rise__c object: $failure")
            Left(failure)
        }
      }

      override def getPriceRise(priceRiseId: String): Either[SalesforceClientFailure, SalesforcePriceRise] =
        try {
          val priceRise = get[SalesforcePriceRise](s"sobjects/Price_Rise__c/$priceRiseId")
          logging.info(
            s"[774f676b] Successfully retrieved Salesforce price rise object, priceRiseId: $priceRiseId, priceRise: $priceRise"
          )
          Right(priceRise)
        } catch {
          case ex: Exception => Left(SalesforceClientFailure(ex.getMessage))
        }
    }
  }

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  val impl: ZLayer[SalesforceConfig with Logging, SalesforceClientFailure, SalesforceClient] =
    ZLayer.fromZIO {
      for {
        config <- ZIO.service[SalesforceConfig]
        logging <- ZIO.service[Logging]
        client <- ZIO
          .attempt(instance(config, logging))
          .mapError(ex => SalesforceClientFailure(ex.getMessage))
      } yield client
    }

  private[pricemigrationengine] def serialisePriceRise(priceRise: SalesforcePriceRise) =
    write(priceRise, indent = 2)
}
