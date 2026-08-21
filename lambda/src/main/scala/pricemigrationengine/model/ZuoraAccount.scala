package pricemigrationengine.model

import pricemigrationengine.model.given
import upickle.default.*

case class ZuoraAccountBasicInfo(accountNumber: String)
object ZuoraAccountBasicInfo {
  given ReadWriter[ZuoraAccountBasicInfo] = macroRW
}

case class ZuoraAccount(
    basicInfo: ZuoraAccountBasicInfo,
    soldToContact: SoldToContact
)

object ZuoraAccount {
  given ReadWriter[ZuoraAccount] = macroRW
}
