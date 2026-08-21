package pricemigrationengine.model

import upickle.default.{ReadWriter, macroRW}

case class ZuoraProductCatalogue(products: Set[ZuoraProduct], nextPage: Option[String] = None)

object ZuoraProductCatalogue {
  given ReadWriter[ZuoraProductCatalogue] = macroRW
  def empty: ZuoraProductCatalogue = ZuoraProductCatalogue(products = Set.empty)
}

case class ZuoraProduct(
    name: String,
    productRatePlans: Set[ZuoraProductRatePlan]
)

object ZuoraProduct {
  given ReadWriter[ZuoraProduct] = macroRW
}

case class ZuoraProductRatePlan(
    id: String,
    name: String,
    status: String,
    productRatePlanCharges: Set[ZuoraProductRatePlanCharge]
)

object ZuoraProductRatePlan {
  given ReadWriter[ZuoraProductRatePlan] = macroRW
}

case class ZuoraProductRatePlanCharge(id: String, billingPeriod: Option[String], pricing: Set[ZuoraPricing])

object ZuoraProductRatePlanCharge {
  given ReadWriter[ZuoraProductRatePlanCharge] = macroRW
}

/*
 * Don't use discount percentage from product catalogue,
 * because it can be overridden so the default value is unreliable.
 */
case class ZuoraPricing(currency: Currency, price: Option[BigDecimal], hasBeenPriceCapped: Boolean = false)

object ZuoraPricing {
  given ReadWriter[ZuoraPricing] = macroRW
}
