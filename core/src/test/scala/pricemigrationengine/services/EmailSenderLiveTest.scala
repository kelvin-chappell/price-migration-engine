package pricemigrationengine.services

import pricemigrationengine.TestLogging
import pricemigrationengine.model.EmailSenderConfig
import pricemigrationengine.model.membershipworkflow.{
  EmailMessage,
  EmailPayload,
  EmailPayloadContactAttributes,
  EmailPayloadSubscriberAttributes
}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  GetQueueUrlRequest,
  GetQueueUrlResponse,
  SendMessageRequest,
  SendMessageResponse
}

class EmailSenderLiveTest extends munit.FunSuite {

  private val message = EmailMessage(
    EmailPayload(
      Some("test@test.com"),
      EmailPayloadContactAttributes(
        EmailPayloadSubscriberAttributes(
          Some("title"),
          "firstName",
          "lastName",
          "address line 1",
          Some("address line 2"),
          Some("town"),
          "postcode",
          Some("county"),
          "country",
          "1.23",
          "2020-01-01",
          "Monthly",
          "Subscription-001",
          "Newspaper - Digital Voucher"
        )
      )
    ),
    "data-extension",
    "contactId",
    Some("identity-user-id")
  )

  test("buildSendMessageRequest is pure: builds the same request for the same inputs, with no I/O") {
    val request = EmailSenderLive.buildSendMessageRequest("https://queue-url", message)

    assertEquals(request.queueUrl, "https://queue-url")
    assert(request.messageBody.contains("contactId"))
  }

  test("serialiseMessage should serialise message correctly") {
    assertEquals(
      EmailSenderLive.serialiseMessage(message),
      """{
        |  "To": {
        |    "Address": "test@test.com",
        |    "ContactAttributes": {
        |      "SubscriberAttributes": {
        |        "title": "title",
        |        "first_name": "firstName",
        |        "last_name": "lastName",
        |        "billing_address_1": "address line 1",
        |        "billing_address_2": "address line 2",
        |        "billing_city": "town",
        |        "billing_postal_code": "postcode",
        |        "billing_state": "county",
        |        "billing_country": "country",
        |        "payment_amount": "1.23",
        |        "next_payment_date": "2020-01-01",
        |        "payment_frequency": "Monthly",
        |        "subscription_id": "Subscription-001",
        |        "product_type": "Newspaper - Digital Voucher"
        |      }
        |    }
        |  },
        |  "DataExtensionName": "data-extension",
        |  "SfContactId": "contactId",
        |  "IdentityUserId": "identity-user-id"
        |}""".stripMargin
    )
  }

  test("serialiseMessage should serialise message with missing optional values correctly") {
    val messageWithMissingOptionals = EmailMessage(
      EmailPayload(
        Some("test@test.com"),
        EmailPayloadContactAttributes(
          EmailPayloadSubscriberAttributes(
            None,
            "firstName",
            "lastName",
            "address line 1",
            None,
            None,
            "postcode",
            None,
            "country",
            "1.23",
            "2020-01-01",
            "Monthly",
            "Subscription-001",
            "Newspaper - Digital Voucher"
          )
        )
      ),
      "data-extension",
      "contactId",
      None
    )

    assertEquals(
      EmailSenderLive.serialiseMessage(messageWithMissingOptionals),
      """{
        |  "To": {
        |    "Address": "test@test.com",
        |    "ContactAttributes": {
        |      "SubscriberAttributes": {
        |        "title": null,
        |        "first_name": "firstName",
        |        "last_name": "lastName",
        |        "billing_address_1": "address line 1",
        |        "billing_address_2": null,
        |        "billing_city": null,
        |        "billing_postal_code": "postcode",
        |        "billing_state": null,
        |        "billing_country": "country",
        |        "payment_amount": "1.23",
        |        "next_payment_date": "2020-01-01",
        |        "payment_frequency": "Monthly",
        |        "subscription_id": "Subscription-001",
        |        "product_type": "Newspaper - Digital Voucher"
        |      }
        |    }
        |  },
        |  "DataExtensionName": "data-extension",
        |  "SfContactId": "contactId",
        |  "IdentityUserId": null
        |}""".stripMargin
    )
  }

  test("instance.sendEmail looks up the queue url once and sends the serialised message via the injected client") {
    var getQueueUrlCalls = 0
    var sentRequest: Option[SendMessageRequest] = None

    val stubSqsClient: SqsClient = new SqsClient {
      override def close(): Unit = ()
      override def serviceName(): String = "sqs"
      override def getQueueUrl(request: GetQueueUrlRequest): GetQueueUrlResponse = {
        getQueueUrlCalls += 1
        GetQueueUrlResponse.builder.queueUrl("https://queue-url").build()
      }
      override def sendMessage(request: SendMessageRequest): SendMessageResponse = {
        sentRequest = Some(request)
        SendMessageResponse.builder.messageId("message-id").build()
      }
    }

    val emailSender =
      EmailSenderLive.instance(EmailSenderConfig("queue-name"), TestLogging.instance, stubSqsClient)

    emailSender.sendEmail(message)
    emailSender.sendEmail(message)

    assertEquals(getQueueUrlCalls, 1)
    assertEquals(sentRequest.map(_.queueUrl), Some("https://queue-url"))
  }
}
