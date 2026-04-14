package com.epam.notifications.service;

import com.epam.notifications.domain.NotificationMessage;
import com.epam.notifications.domain.NotificationRequest;
import com.epam.notifications.domain.NotificationEventView;
import com.epam.notifications.domain.NotificationOverviewResponse;
import com.epam.notifications.persistence.NotificationDeliveryStatus;
import com.epam.notifications.persistence.NotificationEntity;
import com.epam.notifications.persistence.NotificationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class NotificationPersistenceService {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public NotificationPersistenceService(NotificationRepository notificationRepository, ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<String> findExistingIdByIdempotency(String userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return notificationRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(NotificationEntity::getNotificationId);
    }

    @Transactional
    public NotificationEntity createEnqueued(String notificationId, NotificationRequest request) {
        Instant now = Instant.now();
        NotificationEntity entity = new NotificationEntity();
        entity.setNotificationId(notificationId);
        entity.setUserId(request.userId());
        entity.setType(request.type());
        entity.setMessage(request.message());
        entity.setMetadataJson(writeMetadata(request.metadata()));
        entity.setIdempotencyKey(normalizeIdempotencyKey(request.idempotencyKey()));
        entity.setStatus(NotificationDeliveryStatus.ENQUEUED);
        entity.setAttempts(0);
        entity.setNextAttemptAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return notificationRepository.save(entity);
    }

    @Transactional
    public boolean markDelivered(String notificationId) {
        if (notificationId == null || notificationId.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        int updated = notificationRepository.updateDelivered(
                notificationId,
                NotificationDeliveryStatus.DELIVERED,
                List.of(NotificationDeliveryStatus.ENQUEUED, NotificationDeliveryStatus.RETRY_SCHEDULED),
                now,
                now
        );
        return updated > 0;
    }

    @Transactional(readOnly = true)
    public boolean isTerminalStatus(String notificationId) {
        if (notificationId == null || notificationId.isBlank()) {
            return false;
        }
        return notificationRepository.findStatusByNotificationId(notificationId)
                .map(status -> status == NotificationDeliveryStatus.DELIVERED || status == NotificationDeliveryStatus.DEAD_LETTER)
                .orElse(false);
    }

    @Transactional
    public void markRetryScheduled(String notificationId, int attempts, Instant nextAttemptAt, String failedReason) {
        if (notificationId == null || notificationId.isBlank()) {
            return;
        }
        notificationRepository.updateRetryScheduled(
                notificationId,
                NotificationDeliveryStatus.RETRY_SCHEDULED,
                attempts,
                nextAttemptAt,
                failedReason,
                Instant.now()
        );
    }

    @Transactional
    public void markDeadLetter(String notificationId, int attempts, String failedReason) {
        if (notificationId == null || notificationId.isBlank()) {
            return;
        }
        notificationRepository.updateDeadLetter(
                notificationId,
                NotificationDeliveryStatus.DEAD_LETTER,
                attempts,
                failedReason,
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public List<NotificationMessage> loadRecoverable(int maxBatchSize) {
        List<NotificationEntity> records = notificationRepository.findRecoverable(
                List.of(NotificationDeliveryStatus.ENQUEUED, NotificationDeliveryStatus.RETRY_SCHEDULED),
                Instant.now(),
                PageRequest.of(0, maxBatchSize)
        );

        return records.stream()
                .map(entity -> new NotificationMessage(
                        entity.getNotificationId(),
                        entity.getUserId(),
                        entity.getType(),
                        entity.getMessage(),
                        readMetadata(entity.getMetadataJson()),
                        entity.getCreatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public long deliveredCount() {
        return notificationRepository.countByStatus(NotificationDeliveryStatus.DELIVERED);
    }

    @Transactional(readOnly = true)
    public long deadLetterCount() {
        return notificationRepository.countByStatus(NotificationDeliveryStatus.DEAD_LETTER);
    }

    @Transactional(readOnly = true)
    public List<NotificationEventView> recentEvents(int limit) {
        int boundedLimit = sanitizeLimit(limit);
        return notificationRepository.findByOrderByCreatedAtDesc(PageRequest.of(0, boundedLimit))
                .stream()
                .map(this::toEventView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<NotificationEventView> recentFailures(int limit) {
        int boundedLimit = sanitizeLimit(limit);
        return notificationRepository.findByStatusInOrderByUpdatedAtDesc(
                        List.of(NotificationDeliveryStatus.RETRY_SCHEDULED, NotificationDeliveryStatus.DEAD_LETTER),
                        PageRequest.of(0, boundedLimit)
                )
                .stream()
                .map(this::toEventView)
                .toList();
    }

    @Transactional(readOnly = true)
    public NotificationOverviewResponse overview() {
        long enqueued = notificationRepository.countByStatus(NotificationDeliveryStatus.ENQUEUED);
        long retryScheduled = notificationRepository.countByStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
        long delivered = notificationRepository.countByStatus(NotificationDeliveryStatus.DELIVERED);
        long deadLetter = notificationRepository.countByStatus(NotificationDeliveryStatus.DEAD_LETTER);
        long total = enqueued + retryScheduled + delivered + deadLetter;

        double successRate = total == 0 ? 0.0 : (delivered * 100.0) / total;
        double failureRate = total == 0 ? 0.0 : (deadLetter * 100.0) / total;

        Instant from = Instant.now().minus(Duration.ofMinutes(5));
        long recentEvents = notificationRepository.countByCreatedAtAfter(from);
        double throughputPerSecond = recentEvents / 300.0;

        return new NotificationOverviewResponse(
                total,
                enqueued,
                retryScheduled,
                delivered,
                deadLetter,
                successRate,
                failureRate,
                throughputPerSecond
        );
    }

    private NotificationEventView toEventView(NotificationEntity entity) {
        return new NotificationEventView(
                entity.getNotificationId(),
                entity.getUserId(),
                entity.getType(),
                entity.getMessage(),
                entity.getStatus(),
                entity.getAttempts(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeliveredAt(),
                entity.getFailedReason()
        );
    }

    private int sanitizeLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, 200);
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }

    private String writeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize metadata", exception);
        }
    }

    private Map<String, String> readMetadata(String metadataJson) {
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }
}
