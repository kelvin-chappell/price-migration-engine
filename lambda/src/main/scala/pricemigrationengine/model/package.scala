package pricemigrationengine

import java.time.LocalDate

import ujson.{Null, Value}
import upickle.default._

package object model {

  type ZuoraSubscriptionId = String
  type ZuoraProductRatePlanChargeId = String
  type Currency = String
  type ZuoraPricingData = Map[ZuoraProductRatePlanChargeId, ZuoraProductRatePlanCharge]

  given rwLocalDate: ReadWriter[LocalDate] = readwriter[String].bimap[LocalDate](
    date => s"${date.toString}",
    str => LocalDate.parse(str)
  )

  given rwBigDecimal: ReadWriter[BigDecimal] = readwriter[Double].bimap[BigDecimal](
    decimal => decimal.toDouble,
    double => BigDecimal(double)
  )

  given OptionReader[T: Reader]: Reader[Option[T]] = reader[Value].map[Option[T]] {
    case Null    => None
    case jsValue => Some(read[T](jsValue))
  }

  given OptionWriter[T: Writer]: Writer[Option[T]] = writer[T].comap {
    case Some(value) => value
    case None        => null.asInstanceOf[T]
  }
}
