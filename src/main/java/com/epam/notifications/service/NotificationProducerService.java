package com.epam.notifications.service;

import com.epam.notifications.domain.NotificationMessage;
import com.epam.notifications.domain.NotificationRequest;
import com.epam.notifications.domain.NotificationStatusResponse;
import com.epam.notifications.domain.QueuedNotification;
import com.epam.notifications.infra.SimulatedTopicBus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationProducerService {

    public static final String TOPIC_CREATED = "notifications.created";

    private final NotificationPersistenceService notificationPersistenceService;
    private final SimulatedTopicBus simulatedTopicBus;

    public NotificationProducerService(NotificationPersistenceService notificationPersistenceService,
                                       SimulatedTopicBus simulatedTopicBus,
                                       NotificationQueueService notificationQueueService) {
        this.notificationPersistenceService = notificationPersistenceService;
        this.simulatedTopicBus = simulatedTopicBus;

        this.simulatedTopicBus.subscribe(
                TOPIC_CREATED,
                NotificationMessage.class,
                message -> notificationQueueService.enqueue(QueuedNotification.fresh(message))
        );
    }

    public NotificationStatusResponse produce(NotificationRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        var existing = notificationPersistenceService.findExistingIdByIdempotency(request.userId(), idempotencyKey);
        if (existing.isPresent()) {
            return new NotificationStatusResponse(existing.get(), true, "ALREADY_ACCEPTED");
        }

        String notificationId = UUID.randomUUID().toString();
        NotificationMessage message = new NotificationMessage(
                notificationId,
                request.userId(),
                request.type(),
                request.message(),
                request.metadata() == null ? Map.of() : request.metadata(),
                Instant.now()
        );

        NotificationRequest normalizedRequest = new NotificationRequest(
                request.userId(),
                request.type(),
                request.message(),
                idempotencyKey,
                request.metadata()
        );
        notificationPersistenceService.createEnqueued(notificationId, normalizedRequest);

        simulatedTopicBus.publish(TOPIC_CREATED, message);
        return new NotificationStatusResponse(notificationId, false, "ENQUEUED");
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }
}
