package pricemigrationengine.services

import pricemigrationengine.model.membershipworkflow.EmailMessage
import pricemigrationengine.model.EmailSenderFailure
import zio.ZIO

trait EmailSender {

  /** Throws on failure. */
  def sendEmail(message: EmailMessage): Unit
}

object EmailSender {

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  def sendEmail(message: EmailMessage): ZIO[EmailSender, EmailSenderFailure, Unit] =
    ZIO.serviceWithZIO(service =>
      ZIO
        .attempt(service.sendEmail(message))
        .mapError(ex =>
          EmailSenderFailure(
            s"Failed to send sqs email message for sfContactId ${message.SfContactId}: ${ex.getMessage}"
          )
        )
    )
}
