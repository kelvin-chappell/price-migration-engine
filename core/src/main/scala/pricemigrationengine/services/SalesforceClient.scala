package pricemigrationengine.services

import pricemigrationengine.model.{
  SalesforceClientFailure,
  SalesforceContact,
  SalesforcePriceRise,
  SalesforceSubscription
}
import zio.ZIO

case class SalesforcePriceRiseCreationResponse(id: String)

trait SalesforceClient {
  def getSubscriptionByName(subscrptionName: String): Either[SalesforceClientFailure, SalesforceSubscription]
  def getContact(contactId: String): Either[SalesforceClientFailure, SalesforceContact]
  def createPriceRise(
      priceRise: SalesforcePriceRise
  ): Either[SalesforceClientFailure, SalesforcePriceRiseCreationResponse]
  def updatePriceRise(priceRiseId: String, priceRise: SalesforcePriceRise): Either[SalesforceClientFailure, Unit]
  def getPriceRise(priceRiseId: String): Either[SalesforceClientFailure, SalesforcePriceRise]
}

object SalesforceClient {

  /** ZIO-facing compatibility shim for not-yet-converted callers. Preserves the original error messages/types,
    * which are built and returned as values by the direct-style implementation.
    */
  def getSubscriptionByName(
      subscrptionName: String
  ): ZIO[SalesforceClient, SalesforceClientFailure, SalesforceSubscription] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.getSubscriptionByName(subscrptionName)))

  def getContact(
      contactId: String
  ): ZIO[SalesforceClient, SalesforceClientFailure, SalesforceContact] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.getContact(contactId)))

  def createPriceRise(
      priceRise: SalesforcePriceRise
  ): ZIO[SalesforceClient, SalesforceClientFailure, SalesforcePriceRiseCreationResponse] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.createPriceRise(priceRise)))

  def updatePriceRise(
      priceRiseId: String,
      priceRise: SalesforcePriceRise
  ): ZIO[SalesforceClient, SalesforceClientFailure, Unit] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.updatePriceRise(priceRiseId, priceRise)))

  def getPriceRise(
      priceRiseId: String
  ): ZIO[SalesforceClient, SalesforceClientFailure, SalesforcePriceRise] =
    ZIO.serviceWithZIO(service => ZIO.fromEither(service.getPriceRise(priceRiseId)))
}
