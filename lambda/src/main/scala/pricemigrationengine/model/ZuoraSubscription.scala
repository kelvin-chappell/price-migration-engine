package pricemigrationengine.model

import pricemigrationengine.model.given
import upickle.default.*

import java.time.LocalDate

case class ZuoraSubscription(
    subscriptionNumber: String,
    id: String,
    version: Int,
    customerAcceptanceDate: LocalDate,
    contractEffectiveDate: LocalDate,
    subscriptionStartDate: LocalDate,
    ratePlans: List[ZuoraRatePlan],
    accountNumber: String,
    accountId: String,
    status: String,
    termStartDate: LocalDate,
    termEndDate: LocalDate,
    autoRenew: Boolean
)

object ZuoraSubscription {
  given ReadWriter[ZuoraSubscription] = macroRW
}
