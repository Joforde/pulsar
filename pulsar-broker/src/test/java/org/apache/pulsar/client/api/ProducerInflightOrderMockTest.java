package org.apache.pulsar.client.api;

import lombok.Cleanup;
import org.apache.pulsar.broker.service.BrokerTestBase;
import org.apache.pulsar.common.api.proto.ServerError;
import org.apache.pulsar.common.protocol.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test producer inflight message ordering when encountering retriable errors using MockBrokerService
 */
public class ProducerInflightOrderMockTest extends BrokerTestBase {
    private static final Logger log = LoggerFactory.getLogger(ProducerInflightOrderMockTest.class);

    private static final String TOPIC_NAME = "persistent://prop/use/ns/test-inflight-order-mock";
    private static final String SUBSCRIPTION_NAME = "test-sub";
    MockBrokerService mockBrokerService;

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        baseSetup();
        mockBrokerService = new MockBrokerService();
        mockBrokerService.start();
    }

    @AfterMethod
    @Override
    protected void cleanup() throws Exception {
        internalCleanup();
        if (mockBrokerService != null) {
            mockBrokerService.stop();
        }
    }

    @Test(timeOut = 30000)
    public void testInflightMessagesOrderWithMockServiceNotReadyError() throws Exception {
        // 1. Create topic
        admin.topics().createNonPartitionedTopic(TOPIC_NAME);

        @Cleanup PulsarClient client = PulsarClient.builder().serviceUrl(mockBrokerService.getBrokerAddress()).build();

        // 2. Create producer
        Producer<byte[]> producer = client.newProducer().topic(TOPIC_NAME).sendTimeout(5, TimeUnit.SECONDS)
            .enableBatching(true).batchingMaxMessages(1).create();

        // 3. Track received messages on server side
        List<String> receivedMessages = new ArrayList<>();
        AtomicInteger sendCount = new AtomicInteger(0);
        AtomicInteger offset = new AtomicInteger(0);

        // 4. Set up mock broker service to simulate errors
        mockBrokerService.setHandleSend((ctx, send, headersAndPayload) -> {
            int count = sendCount.incrementAndGet();
            if (count == 1) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                // First message returns ServiceNotReady error
                log.info("Mocking ServiceNotReady error for first message");
                ctx.writeAndFlush(Commands.newSendError(send.getProducerId(), send.getSequenceId(),
                    ServerError.ServiceNotReady, "Service not ready"));
            } else {
                // Other messages processed normally - send success response
                Commands.skipMessageMetadata(headersAndPayload);
                byte[] data = new byte[headersAndPayload.readableBytes()];
                headersAndPayload.readBytes(data);
                String content = new String(data, Charset.defaultCharset());
                receivedMessages.add(content);
                log.info("Processing message {} normally, {}", count, content);
                // Send success response
                ctx.writeAndFlush(Commands.newSendReceipt(send.getProducerId(), send.getSequenceId(),
                    send.getHighestSequenceId(), 1L, offset.incrementAndGet()));
            }
        });

        // 5. Send 3 messages asynchronously
        List<CompletableFuture<MessageId>> futures = new ArrayList<>();
        List<String> sentMessages = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            final int messageIndex = i;
            String messageContent = "mock-message-" + messageIndex;
            sentMessages.add(messageContent);

            CompletableFuture<MessageId> future = producer.sendAsync(messageContent.getBytes()).whenComplete((messageId, throwable) -> {
                if (throwable != null) {
                    log.warn("Message {} send failed: {}", messageIndex, throwable.getMessage());
                } else {
                    log.info("Message {} sent successfully: {}, {}", messageIndex, messageId, messageContent);
                }
            });
            futures.add(future);
        }

        // 6. Wait for all messages to complete (including retries)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Some messages may have failed, but this is expected for retriable errors", e);
        }

        // 7. Validate results
        validateMessageOrdering(sentMessages, receivedMessages, sendCount.get());

        // 8. Clean up resources
        mockBrokerService.resetHandleSend();
        producer.close();
    }

    @Test(timeOut = 30000)
    public void testInflightMessagesOrderWithMockConnectionError() throws Exception {
        // 1. Create topic
        admin.topics().createNonPartitionedTopic(TOPIC_NAME);

        @Cleanup PulsarClient client = PulsarClient.builder().serviceUrl(mockBrokerService.getBrokerAddress()).build();

        // 2. Create producer
        Producer<byte[]> producer = client.newProducer().topic(TOPIC_NAME).sendTimeout(5, TimeUnit.SECONDS).enableBatching(false).create();

        // 3. Track received messages on server side
        List<String> receivedMessages = new ArrayList<>();
        AtomicInteger sendCount = new AtomicInteger(0);
        AtomicInteger offset = new AtomicInteger(0);

        // 4. Set up mock broker service to simulate connection errors
        mockBrokerService.setHandleSend((ctx, send, headersAndPayload) -> {
            int count = sendCount.incrementAndGet();
            if (count == 1) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                // Close connection for first message to simulate connection error
                log.info("Mocking connection error for first message");
                ctx.close();
            } else {
                // Other messages processed normally
                Commands.skipMessageMetadata(headersAndPayload);
                byte[] data = new byte[headersAndPayload.readableBytes()];
                headersAndPayload.readBytes(data);
                String content = new String(data, Charset.defaultCharset());
                receivedMessages.add(content);
                log.info("Processing message {} normally, {}", count, content);
                // Send success response
                ctx.writeAndFlush(Commands.newSendReceipt(send.getProducerId(), send.getSequenceId(), send.getHighestSequenceId(), 1L, offset.incrementAndGet()));
            }
        });

        // 5. Send 3 messages asynchronously
        List<CompletableFuture<MessageId>> futures = new ArrayList<>();
        List<String> sentMessages = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            final int messageIndex = i;
            String messageContent = "mock-message-" + messageIndex;
            sentMessages.add(messageContent);

            CompletableFuture<MessageId> future = producer.sendAsync(messageContent.getBytes()).whenComplete((messageId, throwable) -> {
                if (throwable != null) {
                    log.warn("Message {} send failed: {}", messageIndex, throwable.getMessage());
                } else {
                    log.info("Message {} sent successfully: {}, {}", messageIndex, messageId, messageContent);
                }
            });
            futures.add(future);
        }

        // 6. Wait for all messages to complete (including retries)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Some messages may have failed, but this is expected for retriable errors", e);
        }

        // 7. Validate results
        validateMessageOrdering(sentMessages, receivedMessages, sendCount.get());

        // 8. Clean up resources
        mockBrokerService.resetHandleSend();
        producer.close();
    }

    @Test(timeOut = 30000)
    public void testInflightMessagesOrderWithMockTimeoutError() throws Exception {
        // 1. Create topic
        admin.topics().createNonPartitionedTopic(TOPIC_NAME);

        @Cleanup PulsarClient client = PulsarClient.builder().serviceUrl(mockBrokerService.getBrokerAddress()).build();

        // 2. Create producer
        Producer<byte[]> producer = client.newProducer().topic(TOPIC_NAME).sendTimeout(5, TimeUnit.SECONDS).enableBatching(false).create();

        // 3. Track received messages on server side
        List<String> receivedMessages = new ArrayList<>();
        AtomicInteger sendCount = new AtomicInteger(0);
        AtomicInteger offset = new AtomicInteger(0);

        // 4. Set up mock broker service to simulate timeout errors
        mockBrokerService.setHandleSend((ctx, send, headersAndPayload) -> {
            int count = sendCount.incrementAndGet();
            if (count == 1) {
                // Don't respond to first message to simulate timeout
                log.info("Mocking timeout for first message - not responding");
                // Don't call any response, let the message timeout
            } else {
                // Other messages processed normally
                Commands.skipMessageMetadata(headersAndPayload);
                byte[] data = new byte[headersAndPayload.readableBytes()];
                headersAndPayload.readBytes(data);
                String content = new String(data, Charset.defaultCharset());
                receivedMessages.add(content);
                log.info("Processing message {} normally, {}", count, content);
                ctx.writeAndFlush(Commands.newSendReceipt(send.getProducerId(), send.getSequenceId(), send.getHighestSequenceId(), 1L, offset.incrementAndGet()));
            }
        });

        // 5. Send 3 messages asynchronously
        List<CompletableFuture<MessageId>> futures = new ArrayList<>();
        List<String> sentMessages = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            final int messageIndex = i;
            String messageContent = "mock-message-" + messageIndex;
            sentMessages.add(messageContent);

            CompletableFuture<MessageId> future = producer.sendAsync(messageContent.getBytes()).whenComplete((messageId, throwable) -> {
                if (throwable != null) {
                    log.warn("Message {} send failed: {}", messageIndex, throwable.getMessage());
                } else {
                    log.info("Message {} sent successfully: {}, {}", messageIndex, messageId, messageContent);
                }
            });
            futures.add(future);
        }

        // 6. Wait for all messages to complete (including retries)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Some messages may have failed, but this is expected for retriable errors", e);
        }

        // 7. Validate results
        validateMessageOrdering(sentMessages, receivedMessages, sendCount.get());

        // 8. Clean up resources
        mockBrokerService.resetHandleSend();
        producer.close();
    }

    /**
     * Validate that the server receives more messages than sent by client, but the order is consistent
     * send: 1, 2, 3
     * message 1 failed
     * received: 2, 3, 1, 2, 3
     * @param sentMessages List of messages sent by client
     * @param receivedMessages List of messages received by server
     * @param totalReceivedCount Total count of messages received by server
     */
    private void validateMessageOrdering(List<String> sentMessages, List<String> receivedMessages, int totalReceivedCount) {
        log.info("Validation Results:");
        log.info("Sent messages count: {}", sentMessages.size());
        log.info("Received messages count: {}", receivedMessages.size());
        log.info("Total messages processed by server: {}", totalReceivedCount);
        log.info("Sent messages: {}", sentMessages);
        log.info("Received messages: {}", receivedMessages);

        // 1. Verify that server received more messages than client sent (due to retries)
        Assert.assertTrue(totalReceivedCount > sentMessages.size(),
            "Server should receive more messages than client sent due to retries. Expected > " +
            sentMessages.size() + ", but got " + totalReceivedCount);

        // 2. Verify that all sent messages are present in received messages
        for (String sentMessage : sentMessages) {
            Assert.assertTrue(receivedMessages.contains(sentMessage),
                "Sent message '" + sentMessage + "' should be present in received messages");
        }
        // 3. Verify message ordering - extract unique messages in order of first appearance
        LinkedList<String> orderedReceivedMessages = new LinkedList<>();
        for (int i = receivedMessages.size() - 1; i >= 0; i--) {
            String receivedMessage = receivedMessages.get(i);
            if (!orderedReceivedMessages.contains(receivedMessage)) {
                orderedReceivedMessages.addFirst(receivedMessage);
            }
        }
        log.info("Ordered unique messages: {}", orderedReceivedMessages);
        // 4. Verify that the order of unique messages matches the sent order
        Assert.assertEquals(orderedReceivedMessages.size(), sentMessages.size(),
            "Number of unique received messages should match sent messages count");
        for (int i = 0; i < sentMessages.size(); i++) {
            Assert.assertEquals(orderedReceivedMessages.get(i), sentMessages.get(i),
                "Message " + (i + 1) + " should be in correct order. Expected: " +
                sentMessages.get(i) + ", but got: " + orderedReceivedMessages.get(i));
        }

        log.info("Message ordering validation passed successfully!");
    }
}