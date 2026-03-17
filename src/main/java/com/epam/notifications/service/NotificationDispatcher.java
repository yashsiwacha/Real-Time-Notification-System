package com.epam.notifications.service;

import com.epam.notifications.domain.NotificationMessage;
import com.epam.notifications.domain.QueuedNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class NotificationDispatcher {

    private final NotificationQueueService notificationQueueService;
    private final NotificationPersistenceService notificationPersistenceService;
    private final ConnectedUserRegistry connectedUserRegistry;
    private final SimpMessagingTemplate messagingTemplate;
    private final int pollSize;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public NotificationDispatcher(NotificationQueueService notificationQueueService,
                                  NotificationPersistenceService notificationPersistenceService,
                                  ConnectedUserRegistry connectedUserRegistry,
                                  SimpMessagingTemplate messagingTemplate,
                                  @Value("${notification.dispatcher.poll-size:50}") int pollSize,
                                  @Value("${notification.dispatcher.max-attempts:5}") int maxAttempts,
                                  @Value("${notification.dispatcher.base-backoff-ms:500}") long baseBackoffMs) {
        this.notificationQueueService = notificationQueueService;
        this.notificationPersistenceService = notificationPersistenceService;
        this.connectedUserRegistry = connectedUserRegistry;
        this.messagingTemplate = messagingTemplate;
        this.pollSize = pollSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    @Scheduled(fixedDelay = 500)
    public void dispatchReadyNotifications() {
        List<QueuedNotification> ready = notificationQueueService.drainReady(pollSize);
        for (QueuedNotification queued : ready) {
            boolean delivered = tryDeliver(queued.getMessage());
            if (delivered) {
                notificationPersistenceService.markDelivered(queued.getMessage().notificationId());
                continue;
            }

            long backoffMs = computeBackoffMs(queued.getAttempts() + 1);
            queued.markFailedAndScheduleRetry(backoffMs);
            if (queued.getAttempts() >= maxAttempts) {
                notificationQueueService.moveToDeadLetter(queued);
                notificationPersistenceService.markDeadLetter(
                        queued.getMessage().notificationId(),
                        queued.getAttempts(),
                        "Delivery failed after max attempts"
                );
            } else {
                notificationQueueService.enqueue(queued);
                notificationPersistenceService.markRetryScheduled(
                        queued.getMessage().notificationId(),
                        queued.getAttempts(),
                        Instant.ofEpochMilli(queued.getNextAttemptAt()),
                        "User offline or WebSocket delivery failed"
                );
            }
        }
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

    private long computeBackoffMs(int attemptNumber) {
        long calculated = (long) (baseBackoffMs * Math.pow(2, Math.max(0, attemptNumber - 1)));
        return Math.min(calculated, 30_000L);
    }
}
