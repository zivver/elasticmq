package org.elasticmq.rest.sqs

import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.elasticmq.rest.RestServer
import org.elasticmq.{Node, NodeBuilder}
import org.apache.log4j.BasicConfigurator
import org.jboss.netty.logging.{Log4JLoggerFactory, InternalLoggerFactory}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClient}

import scala.collection.JavaConversions._
import com.amazonaws.services.sqs.model._

class AmazonJavaSdkTestSuite extends FunSuite with MustMatchers with BeforeAndAfter {
  val visibilityTimeoutAttribute = "VisibilityTimeout"
  val defaultVisibilityTimeoutAttribute = "VisibilityTimeout"
  val delaySecondsAttribute = "DelaySeconds"

  var node: Node = _
  var server: RestServer = _
  var client: AmazonSQS = _

  BasicConfigurator.configure();
  InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory())

  before {
    node = NodeBuilder.withInMemoryStorage().build()
    server = SQSRestServerFactory.start(node.nativeClient, 8888, "http://localhost:8888")

    client = new AmazonSQSClient(new BasicAWSCredentials("x", "x"))
    client.setEndpoint("http://localhost:8888")
  }

  after {
    client.shutdown()

    server.stop()
    node.shutdown()
  }

  test("should create a queue") {
    client.createQueue(new CreateQueueRequest("testQueue1"))
  }

  test("should get queue url") {
    // Given
    client.createQueue(new CreateQueueRequest("testQueue1"))

    // When
    val queueUrl = client.getQueueUrl(new GetQueueUrlRequest("testQueue1")).getQueueUrl

    // Then
    queueUrl must include ("testQueue1")
  }

  test("should create a queue with the specified visibilty timeout") {
    // When
    client.createQueue(new CreateQueueRequest("testQueue1")
      .withAttributes(Map(defaultVisibilityTimeoutAttribute -> "14")))

    // Then
    val queueUrls = client.listQueues().getQueueUrls

    queueUrls.size() must be (1)

    queueVisibilityTimeout(queueUrls.get(0)) must be (14)
  }

  test("should list created queues") {
    // Given
    client.createQueue(new CreateQueueRequest("testQueue1"))
    client.createQueue(new CreateQueueRequest("testQueue2"))

    // When
    val queueUrls = client.listQueues().getQueueUrls

    // Then
    queueUrls.size() must be (2)

    val setOfQueueUrls = Set() ++ queueUrls
    setOfQueueUrls.find(_.contains("testQueue1")) must be ('defined)
    setOfQueueUrls.find(_.contains("testQueue2")) must be ('defined)
  }

  test("should list queues with the specified prefix") {
    // Given
    client.createQueue(new CreateQueueRequest("aaaQueue"))
    client.createQueue(new CreateQueueRequest("bbbQueue"))

    // When
    val queueUrls = client.listQueues(new ListQueuesRequest().withQueueNamePrefix("aaa")).getQueueUrls

    // Then
    queueUrls.size() must be (1)
    queueUrls.get(0) must include ("aaaQueue")
  }

  test("should create and delete a queue") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl

    // When
    client.deleteQueue(new DeleteQueueRequest(queueUrl))

    // Then
    client.listQueues().getQueueUrls.size() must be (0)
  }

  test("should get queue visibility timeout") {
    // When
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl

    // Then
    queueVisibilityTimeout(queueUrl) must be (30)
  }

  test("should set queue visibility timeout") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl

    // When
    client.setQueueAttributes(new SetQueueAttributesRequest(queueUrl, Map(visibilityTimeoutAttribute -> "10")))

    // Then
    queueVisibilityTimeout(queueUrl) must be (10)
  }

  test("should send and receive a message") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl

    // When
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1"))
    val message = receiveSingleMessage(queueUrl)

    // Then
    message must be (Some("Message 1"))
  }

  test("should block message for the visibility timeout duration") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")
      .withAttributes(Map(defaultVisibilityTimeoutAttribute -> "1"))).getQueueUrl

    // When
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1"))
    val m1 = receiveSingleMessage(queueUrl)
    val m2 = receiveSingleMessage(queueUrl)
    Thread.sleep(1100)
    val m3 = receiveSingleMessage(queueUrl)

    // Then
    m1 must be (Some("Message 1"))
    m2 must be (None)
    m3 must be (Some("Message 1"))
  }

  test("should block message for the specified non-default visibility timeout duration") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl

    // When
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1"))
    val m1 = client.receiveMessage(new ReceiveMessageRequest(queueUrl).withVisibilityTimeout(2)).getMessages.get(0).getBody
    val m2 = receiveSingleMessage(queueUrl)
    Thread.sleep(1100)
    val m3 = receiveSingleMessage(queueUrl)
    Thread.sleep(1100)
    val m4 = receiveSingleMessage(queueUrl)

    // Then
    m1 must be ("Message 1")
    m2 must be (None)
    m3 must be (None)
    m4 must be (Some("Message 1"))
  }

  test("should delete a message") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")
      .withAttributes(Map(defaultVisibilityTimeoutAttribute -> "1"))).getQueueUrl

    // When
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1"))
    val m1 = client.receiveMessage(new ReceiveMessageRequest(queueUrl).withVisibilityTimeout(2)).getMessages.get(0)
    client.deleteMessage(new DeleteMessageRequest(queueUrl, m1.getReceiptHandle))
    Thread.sleep(1100)
    val m2 = receiveSingleMessage(queueUrl)

    // Then
    m1.getBody must be ("Message 1")
    m2 must be (None)
  }

  test("should update message visibility timeout") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")
      .withAttributes(Map(defaultVisibilityTimeoutAttribute -> "1"))).getQueueUrl

    // When
    val msgId = client.sendMessage(new SendMessageRequest(queueUrl, "Message 1")).getMessageId
    client.changeMessageVisibility(new ChangeMessageVisibilityRequest(queueUrl, msgId, 2))

    val m1 = receiveSingleMessage(queueUrl)

    Thread.sleep(1100) // Queue vis timeout - 1 second. The message shouldn't be received yet
    val m2 = receiveSingleMessage(queueUrl)

    Thread.sleep(1100)
    val m3 = receiveSingleMessage(queueUrl)

    // Then
    m1 must be (None)
    m2 must be (None)
    m3 must be (Some("Message 1"))
  }

  test("should read all queue attributes") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1"))
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 2"))
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 3"))
    receiveSingleMessage(queueUrl) // two should remain visible, the received one - invisible

    // When
    val attributes = client.getQueueAttributes(new GetQueueAttributesRequest(queueUrl).withAttributeNames("All")).getAttributes

    // Then
    attributes.get("ApproximateNumberOfMessages") must be ("2")
    attributes.get("ApproximateNumberOfMessagesNotVisible") must be ("1")
    attributes must contain key ("CreatedTimestamp")
    attributes must contain key ("LastModifiedTimestamp")
    attributes must contain key (visibilityTimeoutAttribute)
    attributes must contain key (delaySecondsAttribute)
  }

  test("should read single queue attribute") {
    // Given
    val approximateNumberOfMessagesAttribute = "ApproximateNumberOfMessages"

    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1"))

    // When
    val approximateNumberOfMessages = client
      .getQueueAttributes(new GetQueueAttributesRequest(queueUrl).withAttributeNames(approximateNumberOfMessagesAttribute))
      .getAttributes
      .get(approximateNumberOfMessagesAttribute)
      .toLong

    // Then
    approximateNumberOfMessages must be (1)
  }

  test("should receive message with statistics") {
    // Given
    val start = System.currentTimeMillis()
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")
      .withAttributes(Map(defaultVisibilityTimeoutAttribute -> "1"))).getQueueUrl
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1"))

    val sentTimestampAttribute = "SentTimestamp"
    val approximateReceiveCountAttribute = "ApproximateReceiveCount"
    val approximateFirstReceiveTimestampAttribute = "ApproximateFirstReceiveTimestamp"

    def receiveMessages(): java.util.List[Message] = {
      client.receiveMessage(new ReceiveMessageRequest(queueUrl)
        .withAttributeNames(sentTimestampAttribute, approximateReceiveCountAttribute, approximateFirstReceiveTimestampAttribute))
        .getMessages
    }

    // When
    val messageArray1 = receiveMessages()
    Thread.sleep(1100)
    val messageArray2 = receiveMessages()

    // Then
    messageArray1.size() must be (1)
    val sent1 = messageArray1.get(0).getAttributes.get(sentTimestampAttribute).toLong
    sent1 must be >= (start)
    messageArray1.get(0).getAttributes.get(approximateReceiveCountAttribute).toInt must be (0)
    messageArray1.get(0).getAttributes.get(approximateFirstReceiveTimestampAttribute).toLong must be (0)

    messageArray2.size() must be (1)
    val sent2 = messageArray2.get(0).getAttributes.get(sentTimestampAttribute).toLong
    sent2 must be >= (start)
    messageArray2.get(0).getAttributes.get(approximateReceiveCountAttribute).toInt must be (1)
    messageArray2.get(0).getAttributes.get(approximateFirstReceiveTimestampAttribute).toLong must be >= (start)

    sent1 must be (sent2)
  }

  test("should send delayed message") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl

    // When
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1").withDelaySeconds(1)).getMessageId

    val m1 = receiveSingleMessage(queueUrl)
    Thread.sleep(1100)
    val m2 = receiveSingleMessage(queueUrl)
    val m3 = receiveSingleMessage(queueUrl)

    // Then
    m1 must be (None)
    m2 must be (Some("Message 1"))
    m3 must be (None)
  }

  test("should create delayed queue") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1").withAttributes(Map(delaySecondsAttribute -> "1")))
      .getQueueUrl

    // When
    client.sendMessage(new SendMessageRequest(queueUrl, "Message 1")).getMessageId

    val m1 = receiveSingleMessage(queueUrl)
    Thread.sleep(1100)
    val m2 = receiveSingleMessage(queueUrl)
    val m3 = receiveSingleMessage(queueUrl)

    // Then
    m1 must be (None)
    m2 must be (Some("Message 1"))
    m3 must be (None)
  }

  test("should get queue delay") {
    // When
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl

    // Then
    queueDelay(queueUrl) must be (0)
  }

  test("should set queue delay") {
    // Given
    val queueUrl = client.createQueue(new CreateQueueRequest("testQueue1")).getQueueUrl

    // When
    client.setQueueAttributes(new SetQueueAttributesRequest(queueUrl, Map(delaySecondsAttribute -> "10")))

    // Then
    queueDelay(queueUrl) must be (10)
  }

  def queueVisibilityTimeout(queueUrl: String) = getQueueLongAttribute(queueUrl, visibilityTimeoutAttribute)

  def queueDelay(queueUrl: String) = getQueueLongAttribute(queueUrl, delaySecondsAttribute)

  def getQueueLongAttribute(queueUrl: String, attributeName: String) = {
    client
      .getQueueAttributes(new GetQueueAttributesRequest(queueUrl).withAttributeNames(attributeName))
      .getAttributes.get(attributeName)
      .toLong
  }
  
  def receiveSingleMessage(queueUrl: String) = {
    val messages = client.receiveMessage(new ReceiveMessageRequest(queueUrl)).getMessages
    if (messages.size() == 0) {
      None
    } else {
      Some(messages.get(0).getBody)
    }
  }
}