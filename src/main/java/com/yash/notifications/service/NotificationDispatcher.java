package com.yash.notifications.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import com.yash.notifications.domain.NotificationMessage;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "notification.kafka.enabled", havingValue = "true")
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationPersistenceService notificationPersistenceService;
    private final ConnectedUserRegistry connectedUserRegistry;
    private final NotificationPipelineMetrics notificationPipelineMetrics;
    private final DeliveryCircuitBreaker deliveryCircuitBreaker;
    private final SimpMessagingTemplate messagingTemplate;
    private final long retryDelayMs;
    private final int maxAttempts;

    public NotificationDispatcher(NotificationPersistenceService notificationPersistenceService,
                                  ConnectedUserRegistry connectedUserRegistry,
                                  NotificationPipelineMetrics notificationPipelineMetrics,
                                  DeliveryCircuitBreaker deliveryCircuitBreaker,
                                  SimpMessagingTemplate messagingTemplate,
                                  @Value("${notification.kafka.retry-delay-ms:500}") long retryDelayMs,
                                  @Value("${notification.kafka.delivery-attempts:5}") int maxAttempts) {
        this.notificationPersistenceService = notificationPersistenceService;
        this.connectedUserRegistry = connectedUserRegistry;
        this.notificationPipelineMetrics = notificationPipelineMetrics;
        this.deliveryCircuitBreaker = deliveryCircuitBreaker;
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
    @KafkaListener(
            topics = "${notification.kafka.topic:notifications.created}",
            groupId = "${notification.kafka.group-id:notification-dispatcher}",
            concurrency = "${notification.kafka.consumer-concurrency:3}"
    )
    public void consume(NotificationMessage message,
                        @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        if (notificationPersistenceService.isTerminalStatus(message.notificationId())) {
            log.info("Skipping duplicate Kafka event notificationId={} userId={} reason=terminal_status", message.notificationId(), message.userId());
            notificationPipelineMetrics.onDuplicateSkipped();
            return;
        }

        if (!deliveryCircuitBreaker.allowRequest()) {
            long remainingOpenMs = deliveryCircuitBreaker.remainingOpenMs();
            log.warn("Delivery circuit open notificationId={} userId={} remainingOpenMs={}", message.notificationId(), message.userId(), remainingOpenMs);
            notificationPipelineMetrics.onCircuitOpen();
            scheduleRetry(message, deliveryAttempt, "Circuit breaker open for " + remainingOpenMs + "ms");
            throw new IllegalStateException("Delivery circuit open for notification " + message.notificationId());
        }

        DeliveryOutcome outcome = tryDeliver(message);
        if (outcome == DeliveryOutcome.DELIVERED) {
            deliveryCircuitBreaker.recordSuccess();
            boolean markedDelivered = notificationPersistenceService.markDelivered(message.notificationId());
            if (markedDelivered) {
                notificationPipelineMetrics.onDelivered(message.createdAt());
            } else {
                notificationPipelineMetrics.onDuplicateSkipped();
                log.info("Skipping delivered update for duplicate Kafka event notificationId={} userId={}", message.notificationId(), message.userId());
            }
            return;
        }

        if (outcome == DeliveryOutcome.DISPATCH_FAILED) {
            deliveryCircuitBreaker.recordFailure();
        }

        String reason = switch (outcome) {
            case USER_OFFLINE -> "User offline";
            case INVALID_EVENT -> "Invalid event payload";
            case DISPATCH_FAILED -> "WebSocket dispatch failure";
            default -> "Unknown delivery failure";
        };

        scheduleRetry(message, deliveryAttempt, reason);
        notificationPipelineMetrics.onDeliveryFailed();
        log.warn("Delivery failed notificationId={} userId={} reason={} deliveryAttempt={}",
                message.notificationId(),
                message.userId(),
                reason,
                deliveryAttempt == null ? 1 : deliveryAttempt);
        throw new IllegalStateException("Delivery failed for notification " + message.notificationId());
    }

    @DltHandler
    public void onDeadLetter(NotificationMessage message,
                             @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String dltTopic) {
        String reason = "Moved to DLT topic: " + (dltTopic == null ? "unknown" : dltTopic);
        notificationPersistenceService.markDeadLetter(
                message.notificationId(),
                maxAttempts,
                reason
        );
        notificationPipelineMetrics.onDeadLetter();
        log.error("Notification moved to DLT notificationId={} userId={} reason={}", message.notificationId(), message.userId(), reason);
    }

    private void scheduleRetry(NotificationMessage message, Integer deliveryAttempt, String failureReason) {
        int attempts = deliveryAttempt == null ? 1 : deliveryAttempt;
        long nextAttemptAtMs = Instant.now().toEpochMilli() + retryDelayMs;
        notificationPersistenceService.markRetryScheduled(
                message.notificationId(),
                attempts,
                Instant.ofEpochMilli(nextAttemptAtMs),
                failureReason
        );
        notificationPipelineMetrics.onRetryAttempt();
    }

    private DeliveryOutcome tryDeliver(NotificationMessage message) {
        String userId = message.userId();
        if (userId == null || userId.isBlank()) {
            return DeliveryOutcome.INVALID_EVENT;
        }

        if (!connectedUserRegistry.isConnected(userId)) {
            return DeliveryOutcome.USER_OFFLINE;
        }

        try {
            messagingTemplate.convertAndSendToUser(userId, "/queue/notifications", message);
            return DeliveryOutcome.DELIVERED;
        } catch (Exception exception) {
            log.warn("WebSocket dispatch exception notificationId={} userId={} message={}",
                    message.notificationId(),
                    userId,
                    exception.getMessage());
            return DeliveryOutcome.DISPATCH_FAILED;
        }
    }

    private enum DeliveryOutcome {
        DELIVERED,
        USER_OFFLINE,
        INVALID_EVENT,
        DISPATCH_FAILED
    }
}
