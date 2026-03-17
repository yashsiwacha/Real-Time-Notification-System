package com.epam.notifications.service;

import com.epam.notifications.domain.NotificationMessage;
import com.epam.notifications.domain.NotificationRequest;
import com.epam.notifications.persistence.NotificationDeliveryStatus;
import com.epam.notifications.persistence.NotificationEntity;
import com.epam.notifications.persistence.NotificationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public void markDelivered(String notificationId) {
        if (notificationId == null || notificationId.isBlank()) {
            return;
        }
        notificationRepository.findById(notificationId).ifPresent(entity -> {
            Instant now = Instant.now();
            entity.setStatus(NotificationDeliveryStatus.DELIVERED);
            entity.setDeliveredAt(now);
            entity.setUpdatedAt(now);
            notificationRepository.save(entity);
        });
    }

    @Transactional
    public void markRetryScheduled(String notificationId, int attempts, Instant nextAttemptAt, String failedReason) {
        if (notificationId == null || notificationId.isBlank()) {
            return;
        }
        notificationRepository.findById(notificationId).ifPresent(entity -> {
            entity.setStatus(NotificationDeliveryStatus.RETRY_SCHEDULED);
            entity.setAttempts(attempts);
            entity.setNextAttemptAt(nextAttemptAt);
            entity.setFailedReason(failedReason);
            entity.setUpdatedAt(Instant.now());
            notificationRepository.save(entity);
        });
    }

    @Transactional
    public void markDeadLetter(String notificationId, int attempts, String failedReason) {
        if (notificationId == null || notificationId.isBlank()) {
            return;
        }
        notificationRepository.findById(notificationId).ifPresent(entity -> {
            entity.setStatus(NotificationDeliveryStatus.DEAD_LETTER);
            entity.setAttempts(attempts);
            entity.setFailedReason(failedReason);
            entity.setUpdatedAt(Instant.now());
            notificationRepository.save(entity);
        });
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
