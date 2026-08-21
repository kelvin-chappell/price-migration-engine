package pricemigrationengine.services

import java.time.LocalDate

import pricemigrationengine.model._
import ujson._
import zio.ZIO

trait Zuora {

  /** Returns `Left(ZuoraFetchFailure)` (with the same message the ZIO version would have carried) on failure,
    * instead of throwing - the failure and its context are values, not exceptions.
    */
  def fetchSubscription(subscriptionNumber: String): Either[ZuoraFetchFailure, ZuoraSubscription]

  def fetchAccount(
      accountNumber: String,
      subscriptionNumber: String
  ): Either[ZuoraFetchFailure, ZuoraAccount]

  def fetchInvoicePreview(
      accountId: String,
      targetDate: LocalDate
  ): Either[ZuoraFetchFailure, ZuoraInvoiceList]

  def fetchProductCatalogue(): Either[ZuoraFetchFailure, ZuoraProductCatalogue]

  /*
    This function takes a Zuora Orders API payload and submits it for asynchronous processing.
    Note that the `subscriptionNumber` and `operationDescriptionForLogging` are both
    only used for logging. Notably `operationDescriptionForLogging` was introduced
    simply to specify a difference between renewals and product changes.

    Returns `Left(ZuoraAsynchronousOrderRequestFailure)` on failure.
   */
  def applyOrderAsynchronously(
      subscriptionNumber: String,
      payload: Value,
      operationDescriptionForLogging: String
  ): Either[ZuoraAsynchronousOrderRequestFailure, Unit]
}

object Zuora {

  /** ZIO-facing compatibility shim for not-yet-converted callers. Preserves the original error messages/types,
    * which are built and returned as values by the direct-style implementation.
    */
  def fetchSubscription(subscriptionNumber: String): ZIO[Zuora, ZuoraFetchFailure, ZuoraSubscription] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.fetchSubscription(subscriptionNumber)))

  def fetchAccount(accountNumber: String, subscriptionNumber: String): ZIO[Zuora, ZuoraFetchFailure, ZuoraAccount] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.fetchAccount(accountNumber, subscriptionNumber)))

  def fetchInvoicePreview(accountId: String, targetDate: LocalDate): ZIO[Zuora, ZuoraFetchFailure, ZuoraInvoiceList] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.fetchInvoicePreview(accountId, targetDate)))

  val fetchProductCatalogue: ZIO[Zuora, ZuoraFetchFailure, ZuoraProductCatalogue] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.fetchProductCatalogue()))

  def applyOrderAsynchronously(
      subscriptionNumber: String,
      payload: Value,
      operationDescriptionForLogging: String
  ): ZIO[Zuora, ZuoraAsynchronousOrderRequestFailure, Unit] =
    ZIO.serviceWithZIO(service =>
      ZIO.fromEither(
        service.applyOrderAsynchronously(subscriptionNumber, payload, operationDescriptionForLogging)
      )
    )
}
