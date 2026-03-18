package com.epam.notifications.service;

import com.epam.notifications.domain.NotificationMessage;
import com.epam.notifications.domain.NotificationRequest;
import com.epam.notifications.domain.NotificationStatusResponse;
import com.epam.notifications.domain.QueuedNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationProducerService {

    public static final String TOPIC_CREATED = "notifications.created";

    private final NotificationPersistenceService notificationPersistenceService;
    private final NotificationQueueService notificationQueueService;
    private final KafkaTemplate<String, NotificationMessage> kafkaTemplate;
    private final NotificationPipelineMetrics notificationPipelineMetrics;
    private final boolean kafkaEnabled;
    private final String kafkaTopic;

    public NotificationProducerService(NotificationPersistenceService notificationPersistenceService,
                                       NotificationQueueService notificationQueueService,
                                       KafkaTemplate<String, NotificationMessage> kafkaTemplate,
                                       NotificationPipelineMetrics notificationPipelineMetrics,
                                       @Value("${notification.kafka.enabled:false}") boolean kafkaEnabled,
                                       @Value("${notification.kafka.topic:notifications.created}") String kafkaTopic) {
        this.notificationPersistenceService = notificationPersistenceService;
        this.notificationQueueService = notificationQueueService;
        this.kafkaTemplate = kafkaTemplate;
        this.notificationPipelineMetrics = notificationPipelineMetrics;
        this.kafkaEnabled = kafkaEnabled;
        this.kafkaTopic = kafkaTopic;
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
        notificationPipelineMetrics.onEnqueued();

        if (kafkaEnabled) {
            kafkaTemplate.send(kafkaTopic, message.userId(), message);
        } else {
            notificationQueueService.enqueue(QueuedNotification.fresh(message));
        }
        return new NotificationStatusResponse(notificationId, false, "ENQUEUED");
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }
}
