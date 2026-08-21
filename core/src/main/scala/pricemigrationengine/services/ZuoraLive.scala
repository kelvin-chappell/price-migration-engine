package pricemigrationengine.services

import pricemigrationengine.model._
import sttp.client4._
import sttp.client4.httpclient.HttpClientSyncBackend
import sttp.model.Uri
import ujson._
import upickle.default.{read, write, ReadWriter, Reader, macroRW}

import java.time.LocalDate
import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}
import zio.{ZIO, ZLayer}

object ZuoraLive {

  private val apiVersion = "v1"

  private val readTimeout: Duration = Duration(120, SECONDS)

  private case class AccessToken(access_token: String)
  private implicit val rwAccessToken: ReadWriter[AccessToken] = macroRW

  private case class InvoicePreviewRequest(
      accountId: String,
      targetDate: LocalDate,
      assumeRenewal: String,
      chargeTypeToExclude: String
  )
  private implicit val rwInvoicePreviewRequest: ReadWriter[InvoicePreviewRequest] = macroRW

  /** Pure: wraps an already-correctly-escaped URL string in a `Uri`. Uses `unsafeParse` rather than `Uri(string)`
    * because the latter would re-escape an already-valid URL (needed since `.patch`/query-param building
    * requires a `Uri`, not a raw string). No I/O.
    */
  private[services] def makeURI(url: String): Uri = Uri.unsafeParse(url)

  /** Pure: builds the OAuth2 client-credentials token request. No I/O. */
  private[services] def buildTokenRequest(config: ZuoraConfig): Request[String] =
    basicRequest
      .post(makeURI(s"${config.apiHost}/oauth/token"))
      .body(
        Map(
          "grant_type" -> "client_credentials",
          "client_id" -> config.clientId,
          "client_secret" -> config.clientSecret
        )
      )
      .header("Content-Type", "application/x-www-form-urlencoded")
      .response(asStringAlways)
      .readTimeout(readTimeout)

  /** Pure: parses the access token out of a successful token-request response body. */
  private[services] def parseAccessToken(body: String): String = read[AccessToken](body).access_token

  /** Pure: builds a GET request against a Zuora REST path. No I/O. */
  private[services] def buildGetRequest(
      config: ZuoraConfig,
      path: String,
      params: Map[String, String]
  ): Request[String] =
    basicRequest
      .get(makeURI(s"${config.apiHost}/$apiVersion/$path").addParams(params))
      .header("Authorization", s"******")
      .response(asStringAlways)

  /** Pure: builds a POST request against a Zuora REST path. No I/O. */
  private[services] def buildPostRequest(config: ZuoraConfig, path: String, body: String): Request[String] =
    basicRequest
      .post(makeURI(s"${config.apiHost}/$apiVersion/$path"))
      .body(body)
      .header("Authorization", s"******")
      .contentType("application/json")
      .response(asStringAlways)
      .readTimeout(readTimeout)

  /** Pure: describes a failed Zuora HTTP response, matching the original error-message format. */
  private[services] def describeFailedResponse(
      errorCode: String,
      request: Request[String],
      response: Response[String]
  ): String =
    s"""
       |(error: $errorCode)
       |Zuora request failed
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
    else throw new RuntimeException(describeFailedResponse("5b325714", request, response))
  }

  private def fetchAccessToken(config: ZuoraConfig, backend: SyncBackend): String = {
    val request = buildTokenRequest(config)
    val response =
      try {
        val r = backend.send(request)
        if (!r.code.isSuccess) throw new RuntimeException(describeFailedResponse("8b79c2ed", request, r))
        r
      } catch {
        case ex: RuntimeException => throw ex
        case ex: Exception        =>
          throw new RuntimeException(s"Request for ${request.method} ${request.uri} failed: $ex")
      }
    try parseAccessToken(response.body)
    catch {
      case _: Exception => throw new RuntimeException(s"Failed to parse access token: ${response.body}")
    }
  }

  private def performRequestAndParseAnswer[A](request: Request[String], backend: SyncBackend)(implicit
      reader: Reader[A]
  ): A = {
    val body = performRequest(request, backend).body
    try read[A](body)
    catch {
      case ex: Exception => throw new RuntimeException(s"[c6691aea] failed to deserialise: $body, error: $ex")
    }
  }

  /** Plain, direct-style implementation. Throws on failure.
    *
    * The access token is fetched once, eagerly, at construction time - matching the previous
    * ZLayer-construction-time behaviour. (It's fetched purely to validate the credentials and log the token;
    * it isn't otherwise used - the per-request `Authorization` header is a redacted placeholder in this
    * codebase, matching the pre-existing behaviour being preserved here.)
    */
  def instance(
      config: ZuoraConfig,
      logging: Logging,
      backend: SyncBackend = HttpClientSyncBackend()
  ): Zuora = {
    val accessToken = fetchAccessToken(config, backend)
    logging.info(s"Fetched Zuora access token: $accessToken")

    def retry[A](f: => A): A = Retry.retry(times = 5, delay = FiniteDuration(1, "second"))(f)

    def get[A: Reader](path: String, params: Map[String, String] = Map.empty): A =
      retry(performRequestAndParseAnswer[A](buildGetRequest(config, path, params), backend))

    def post[A: Reader](path: String, body: String): A =
      performRequestAndParseAnswer[A](buildPostRequest(config, path, body), backend)

    new Zuora {
      override def fetchSubscription(subscriptionNumber: String): Either[ZuoraFetchFailure, ZuoraSubscription] = {
        try {
          val result = get[ZuoraSubscription](s"subscriptions/$subscriptionNumber")
          logging.info(s"[4f1645c4] Fetched subscription $subscriptionNumber")
          Right(result)
        } catch {
          case ex: Exception =>
            val message = s"Subscription $subscriptionNumber: ${ex.getMessage}"
            logging.error(s"[4b4b9e39] Failed to fetch subscription $subscriptionNumber: $message")
            Left(ZuoraFetchFailure(message))
        }
      }

      override def fetchAccount(
          accountNumber: String,
          subscriptionNumber: String
      ): Either[ZuoraFetchFailure, ZuoraAccount] = {
        try {
          val result = get[ZuoraAccount](s"accounts/$accountNumber")
          logging.info(s"[7951c941] Fetched account $accountNumber for subscription $subscriptionNumber")
          Right(result)
        } catch {
          case ex: Exception =>
            val message = s"[2b254d19] Account $accountNumber for subscription $subscriptionNumber: ${ex.getMessage}"
            logging.error(
              s"[8a07429d] Failed to fetch account $accountNumber for subscription $subscriptionNumber: $message"
            )
            Left(ZuoraFetchFailure(message))
        }
      }

      // See https://www.zuora.com/developer/api-reference/#operation/POST_BillingPreviewRun
      override def fetchInvoicePreview(
          accountId: String,
          targetDate: LocalDate
      ): Either[ZuoraFetchFailure, ZuoraInvoiceList] = {
        try {
          val result = retry(
            post[ZuoraInvoiceList](
              path = "operations/billing-preview",
              body = write(
                InvoicePreviewRequest(
                  accountId,
                  targetDate,
                  assumeRenewal = "Autorenew",
                  chargeTypeToExclude = "OneTime"
                )
              )
            )
          )
          logging.info(s"[45d846a0] Fetched invoice preview for account $accountId")
          Right(result)
        } catch {
          case ex: Exception =>
            val message = s"[9445e7fd] Invoice preview for account $accountId: ${ex.getMessage}"
            logging.error(s"[26de9125] Failed to fetch invoice preview for account $accountId: $message")
            Left(ZuoraFetchFailure(message))
        }
      }

      override def fetchProductCatalogue(): Either[ZuoraFetchFailure, ZuoraProductCatalogue] = {
        def fetchPage(idx: Int): ZuoraProductCatalogue =
          try {
            val result = get[ZuoraProductCatalogue](path = "catalog/products", params = Map("page" -> idx.toString))
            logging.info(s"[50dbdee6] Fetched product catalogue page $idx")
            result
          } catch {
            case ex: Exception =>
              val message = s"Product catalogue: ${ex.getMessage}"
              logging.error(s"[fdf4fe69] Failed to fetch product catalogue page $idx: $message")
              throw new RuntimeException(message)
          }

        def hasNextPage(catalogue: ZuoraProductCatalogue) = catalogue.nextPage.isDefined

        def combine(c1: ZuoraProductCatalogue, c2: ZuoraProductCatalogue) =
          ZuoraProductCatalogue(products = c1.products ++ c2.products)

        def fetchCatalogue(acc: ZuoraProductCatalogue, pageIdx: Int): ZuoraProductCatalogue = {
          val curr = fetchPage(pageIdx)
          val soFar = combine(acc, curr)
          if (hasNextPage(curr)) fetchCatalogue(soFar, pageIdx + 1) else soFar
        }

        try Right(fetchCatalogue(ZuoraProductCatalogue.empty, pageIdx = 1))
        catch {
          case ex: Exception => Left(ZuoraFetchFailure(ex.getMessage))
        }
      }

      private def submitAsynchronousOrderRequest(
          subscriptionNumber: String,
          payload: Value
      ): AsyncJobSubmissionTicket =
        try post[AsyncJobSubmissionTicket](path = "async/orders", body = payload.toString())
        catch {
          case ex: Exception =>
            throw new RuntimeException(
              s"[c8158a4f] subscription number: $subscriptionNumber, payload: $payload, reason: ${ex.getMessage}"
            )
        }

      private def getJobReport(jobId: String): AsyncJobReport =
        try get[AsyncJobReport](s"async-jobs/$jobId")
        catch {
          case ex: Exception =>
            throw new RuntimeException(
              s"[deb14905] Could not retrieve job report for jobId: $jobId, reason: ${ex.getMessage}"
            )
        }

      /** Polls a submitted async order job every 2 seconds, for up to 150 attempts (5 minutes), until it
        * reaches a terminal state. Returns `Right(())` on success, `Left(message)` if Zuora reports a
        * failure/pending-completion, and throws (via `Retry.pollUntil`'s "gave up" exception) if the job never
        * reaches a terminal state within the polling window.
        */
      private def jobMonitor(jobId: String): Either[String, Unit] =
        Retry
          .pollUntil(maxAttempts = 150, delay = FiniteDuration(2, "seconds")) {
            val jobReport = getJobReport(jobId)
            logging.info(s"[4b4e379c] jobReport: $jobReport")
            if (AsyncJobReport.isCompletedCompleted(jobReport)) Some(Right(()))
            else if (AsyncJobReport.isCompleted(jobReport))
              Some(
                Left(
                  "[8a50192a] The process has completed but not with a completed result. Should investigate. (Possibly and order in Pending state)."
                )
              )
            else if (AsyncJobReport.hasFailed(jobReport))
              Some(Left(jobReport.errors.getOrElse("(empty errors string from the job report)")))
            else None
          }(_.isDefined)
          .get

      override def applyOrderAsynchronously(
          subscriptionNumber: String,
          payload: Value,
          operationDescriptionForLogging: String
      ): Either[ZuoraAsynchronousOrderRequestFailure, Unit] = {
        // Note: This function was introduced in August 2025, to support the print migrations.
        // Some subscriptions with a large number of amendments would timeout during a
        // synchronous renewal or price amendment request, but extensive tests ran
        // with asynchronous requests showed a 100% success rate within less than 20 seconds.
        //
        // If Zuora doesn't complete the job within the polling window (5 minutes), this returns
        // a Left and the caller is expected to fail the lambda invocation - a subsequent retry of
        // the lambda is expected to pick the subscription back up in whatever state Zuora left it in.
        logging.info(
          s"[18943ad2] submitting asynchronous order for subscription $subscriptionNumber, operation: $operationDescriptionForLogging, payload: $payload"
        )

        try {
          val submissionTicket =
            try submitAsynchronousOrderRequest(subscriptionNumber, payload)
            catch {
              case ex: Exception =>
                throw new RuntimeException(
                  s"[847e2075] error while submitting asynchronous order for subscription $subscriptionNumber, operation: $operationDescriptionForLogging, reason: ${ex.getMessage}"
                )
            }

          if (!submissionTicket.success)
            throw new RuntimeException(
              s"[65d1d2fa] Zuora has not accepted an asynchronous order for $subscriptionNumber, operation: $operationDescriptionForLogging, payload: $payload"
            )

          logging.info(
            s"[fe478094] submitted asynchronous order for subscription $subscriptionNumber, operation: $operationDescriptionForLogging, submission ticket: $submissionTicket"
          )

          val monitorResult =
            try jobMonitor(submissionTicket.jobId)
            catch {
              case ex: Exception =>
                throw new RuntimeException(
                  s"[462b80c6] error while evaluating asynchronous job report \uD83E\uDD14, jobId: ${submissionTicket.jobId}, error: $ex"
                )
            }

          monitorResult match {
            case Left(e) =>
              throw new RuntimeException(
                s"[5eed7eb0] We got a Left from the monitor \uD83E\uDD14, jobId: ${submissionTicket.jobId}, error: $e"
              )
            case Right(()) => ()
          }

          logging.info(
            s"[62d66c48] completed asynchronous order for subscription $subscriptionNumber, operation: $operationDescriptionForLogging"
          )
          Right(())
        } catch {
          case ex: Exception => Left(ZuoraAsynchronousOrderRequestFailure(ex.getMessage))
        }
      }
    }
  }

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  val impl: ZLayer[ZuoraConfig with Logging, ConfigFailure, Zuora] =
    ZLayer.fromZIO {
      for {
        logging <- ZIO.service[Logging]
        config <- ZIO.service[ZuoraConfig]
        zuora <- ZIO
          .attempt(instance(config, logging))
          .mapError(ex => ConfigFailure(ex.getMessage))
      } yield zuora
    }
}
