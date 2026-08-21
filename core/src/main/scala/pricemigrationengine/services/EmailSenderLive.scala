package pricemigrationengine.services

import pricemigrationengine.model.{EmailSenderConfig, EmailSenderFailure}
import pricemigrationengine.model.membershipworkflow.EmailMessage
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{GetQueueUrlRequest, SendMessageRequest}
import upickle.default.write
import zio.{ZIO, ZLayer}

/*
  The email sender takes the information in the supplied EmailMessage object
  and sends it to the membership-workflow app via the contribution-thanks sqs queue.

  Membership workflow will then trigger the braze campaign associated with the DataExtensionName
  in the sqs message.

  If the notification is meant to result in a letter being sent, then braze will be configured to
  trigger a 'web-hook'. The web hook is essentially an api call to Latcham our direct mail partner,
  who will use the information in the web hook to print a physical letter notifying the customer
  of the price rise and send it to the customer.

  In other migrations, for instance the membership migration, an email is sent to the customer.
 */

object EmailSenderLive {

  /** Pure: serialises the message to the JSON body sent on the queue. No I/O. */
  private[pricemigrationengine] def serialiseMessage(message: EmailMessage): String =
    write(message, indent = 2)

  /** Pure: builds the send-message request from a queue url and message. No I/O. */
  private[services] def buildSendMessageRequest(queueUrl: String, message: EmailMessage): SendMessageRequest =
    SendMessageRequest.builder
      .queueUrl(queueUrl)
      .messageBody(serialiseMessage(message))
      .build()

  /** Plain, direct-style implementation. Throws on failure. Looks up the queue url once, eagerly, matching the
    * previous ZLayer-construction-time behaviour; sending each message is the only per-call I/O.
    */
  def instance(config: EmailSenderConfig, logging: Logging, sqsClient: SqsClient = AwsClient.sqs): EmailSender = {
    val queueUrl =
      sqsClient.getQueueUrl(GetQueueUrlRequest.builder.queueName(config.sqsEmailQueueName).build()).queueUrl

    message => {
      val result = sqsClient.sendMessage(buildSendMessageRequest(queueUrl, message))
      logging.info(
        s"Successfully sent email for sfContactId ${message.SfContactId} message id: ${result.messageId}, message: $message"
      )
    }
  }

  /** ZIO-facing compatibility shim for not-yet-converted callers. Wraps the queue-url lookup done eagerly by
    * `instance()` so a failure there surfaces as a typed `EmailSenderFailure` (with the same message as the
    * original ZIO implementation), not an unhandled defect.
    */
  val impl: ZLayer[Logging with EmailSenderConfig, EmailSenderFailure, EmailSender] =
    ZLayer.fromZIO {
      for {
        logging <- ZIO.service[Logging]
        config <- ZIO.service[EmailSenderConfig]
        emailSender <- ZIO
          .attempt(instance(config, logging))
          .mapError(ex => EmailSenderFailure(s"Failed to get sqs queue url: ${ex.getMessage}"))
      } yield emailSender
    }
}
