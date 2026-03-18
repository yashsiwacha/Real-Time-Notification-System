package com.epam.notifications.service;

import com.epam.notifications.domain.NotificationMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "notification.kafka.enabled", havingValue = "true")
public class NotificationDispatcher {

    private final NotificationPersistenceService notificationPersistenceService;
    private final ConnectedUserRegistry connectedUserRegistry;
    private final NotificationPipelineMetrics notificationPipelineMetrics;
    private final SimpMessagingTemplate messagingTemplate;
    private final long retryDelayMs;
    private final int maxAttempts;

    public NotificationDispatcher(NotificationPersistenceService notificationPersistenceService,
                                  ConnectedUserRegistry connectedUserRegistry,
                                  NotificationPipelineMetrics notificationPipelineMetrics,
                                  SimpMessagingTemplate messagingTemplate,
                                  @Value("${notification.kafka.retry-delay-ms:500}") long retryDelayMs,
                                  @Value("${notification.kafka.delivery-attempts:5}") int maxAttempts) {
        this.notificationPersistenceService = notificationPersistenceService;
        this.connectedUserRegistry = connectedUserRegistry;
        this.notificationPipelineMetrics = notificationPipelineMetrics;
        this.messagingTemplate = messagingTemplate;
        this.retryDelayMs = retryDelayMs;
        this.maxAttempts = maxAttempts;
    }

    @RetryableTopic(
            attempts = "${notification.kafka.delivery-attempts:5}",
            autoCreateTopics = "true",
            dltTopicSuffix = "-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            backoff = @Backoff(
                    delayExpression = "${notification.kafka.retry-delay-ms:500}",
                    multiplierExpression = "${notification.kafka.retry-multiplier:2.0}",
                    maxDelayExpression = "${notification.kafka.retry-max-delay-ms:30000}"
            )
    )
    @KafkaListener(topics = "${notification.kafka.topic:notifications.created}", groupId = "${notification.kafka.group-id:notification-dispatcher}")
    public void consume(NotificationMessage message,
                        @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        if (tryDeliver(message)) {
            notificationPersistenceService.markDelivered(message.notificationId());
            notificationPipelineMetrics.onDelivered(message.createdAt());
            return;
        }

        int attempts = deliveryAttempt == null ? 1 : deliveryAttempt;
        long nextAttemptAtMs = Instant.now().toEpochMilli() + retryDelayMs;
        notificationPersistenceService.markRetryScheduled(
                message.notificationId(),
                attempts,
                Instant.ofEpochMilli(nextAttemptAtMs),
                "User offline or WebSocket delivery failed"
        );
        notificationPipelineMetrics.onRetryAttempt();
        throw new IllegalStateException("Delivery failed for notification " + message.notificationId());
    }

    @DltHandler
    public void onDeadLetter(NotificationMessage message,
                             @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String dltTopic) {
        notificationPersistenceService.markDeadLetter(
                message.notificationId(),
                maxAttempts,
                "Moved to DLT topic: " + (dltTopic == null ? "unknown" : dltTopic)
        );
        notificationPipelineMetrics.onDeadLetter();
    }

    private boolean tryDeliver(NotificationMessage message) {
        String userId = message.userId();
        if (userId == null || userId.isBlank()) {
            return false;
        }

        if (!connectedUserRegistry.isConnected(userId)) {
            return false;
        }

        try {
            messagingTemplate.convertAndSendToUser(userId, "/queue/notifications", message);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
