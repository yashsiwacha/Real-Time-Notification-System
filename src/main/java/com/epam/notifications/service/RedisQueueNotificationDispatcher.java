package com.epam.notifications.service;

import com.epam.notifications.domain.NotificationMessage;
import com.epam.notifications.domain.QueuedNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(name = "notification.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class RedisQueueNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RedisQueueNotificationDispatcher.class);

    private final NotificationQueueService notificationQueueService;
    private final NotificationPersistenceService notificationPersistenceService;
    private final ConnectedUserRegistry connectedUserRegistry;
    private final NotificationPipelineMetrics notificationPipelineMetrics;
    private final DeliveryCircuitBreaker deliveryCircuitBreaker;
    private final SimpMessagingTemplate messagingTemplate;
    private final int pollSize;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public RedisQueueNotificationDispatcher(NotificationQueueService notificationQueueService,
                                            NotificationPersistenceService notificationPersistenceService,
                                            ConnectedUserRegistry connectedUserRegistry,
                                            NotificationPipelineMetrics notificationPipelineMetrics,
                                            DeliveryCircuitBreaker deliveryCircuitBreaker,
                                            SimpMessagingTemplate messagingTemplate,
                                            @Value("${notification.dispatcher.poll-size:50}") int pollSize,
                                            @Value("${notification.dispatcher.max-attempts:5}") int maxAttempts,
                                            @Value("${notification.dispatcher.base-backoff-ms:500}") long baseBackoffMs) {
        this.notificationQueueService = notificationQueueService;
        this.notificationPersistenceService = notificationPersistenceService;
        this.connectedUserRegistry = connectedUserRegistry;
        this.notificationPipelineMetrics = notificationPipelineMetrics;
        this.deliveryCircuitBreaker = deliveryCircuitBreaker;
        this.messagingTemplate = messagingTemplate;
        this.pollSize = pollSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    @Scheduled(fixedDelay = 500)
    public void dispatchReadyNotifications() {
        List<QueuedNotification> ready = notificationQueueService.drainReady(pollSize);
        for (QueuedNotification queued : ready) {
            String notificationId = queued.getMessage().notificationId();
            if (notificationPersistenceService.isTerminalStatus(notificationId)) {
                notificationPipelineMetrics.onDuplicateSkipped();
                log.info("Skipping duplicate queued event notificationId={} userId={}", notificationId, queued.getMessage().userId());
                continue;
            }

            if (!deliveryCircuitBreaker.allowRequest()) {
                long backoffMs = Math.max(computeBackoffMs(queued.getAttempts() + 1), deliveryCircuitBreaker.remainingOpenMs());
                queued.markFailedAndScheduleRetry(backoffMs);
                notificationQueueService.enqueue(queued);
                notificationPersistenceService.markRetryScheduled(
                        notificationId,
                        queued.getAttempts(),
                        Instant.ofEpochMilli(queued.getNextAttemptAt()),
                        "Circuit breaker open"
                );
                notificationPipelineMetrics.onRetryAttempt();
                notificationPipelineMetrics.onCircuitOpen();
                continue;
            }

            DeliveryOutcome outcome = tryDeliver(queued.getMessage());
            if (outcome == DeliveryOutcome.DELIVERED) {
                deliveryCircuitBreaker.recordSuccess();
                boolean markedDelivered = notificationPersistenceService.markDelivered(notificationId);
                if (markedDelivered) {
                    notificationPipelineMetrics.onDelivered(queued.getMessage().createdAt());
                } else {
                    notificationPipelineMetrics.onDuplicateSkipped();
                }
                continue;
            }

            if (outcome == DeliveryOutcome.DISPATCH_FAILED) {
                deliveryCircuitBreaker.recordFailure();
            }

            long backoffMs = computeBackoffMs(queued.getAttempts() + 1);
            queued.markFailedAndScheduleRetry(backoffMs);
            if (queued.getAttempts() >= maxAttempts) {
                notificationQueueService.moveToDeadLetter(queued);
                notificationPersistenceService.markDeadLetter(
                        notificationId,
                        queued.getAttempts(),
                        "Delivery failed after max attempts"
                );
                notificationPipelineMetrics.onDeadLetter();
                notificationPipelineMetrics.onDeliveryFailed();
                log.error("Queue delivery moved to dead letter notificationId={} userId={} attempts={}",
                        notificationId,
                        queued.getMessage().userId(),
                        queued.getAttempts());
            } else {
                notificationQueueService.enqueue(queued);
                notificationPersistenceService.markRetryScheduled(
                        notificationId,
                        queued.getAttempts(),
                        Instant.ofEpochMilli(queued.getNextAttemptAt()),
                        outcome == DeliveryOutcome.USER_OFFLINE ? "User offline" : "WebSocket delivery failed"
                );
                notificationPipelineMetrics.onRetryAttempt();
                notificationPipelineMetrics.onDeliveryFailed();
            }
        }
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

    private long computeBackoffMs(int attemptNumber) {
        long calculated = (long) (baseBackoffMs * Math.pow(2, Math.max(0, attemptNumber - 1)));
        return Math.min(calculated, 30_000L);
    }

    private enum DeliveryOutcome {
        DELIVERED,
        USER_OFFLINE,
        INVALID_EVENT,
        DISPATCH_FAILED
    }
}
