package com.epam.notifications.service;

import com.epam.notifications.domain.QueuedNotification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class NotificationQueueService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String readyQueueKey;
    private final String payloadKeyPrefix;
    private final String deadLetterCounterKey;

    public NotificationQueueService(StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    @Value("${notification.queue.key-prefix:notification}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.readyQueueKey = keyPrefix + ":queue:ready";
        this.payloadKeyPrefix = keyPrefix + ":queue:payload:";
        this.deadLetterCounterKey = keyPrefix + ":queue:dead-letter-count";
    }

    public void enqueue(QueuedNotification queuedNotification) {
        if (queuedNotification == null || queuedNotification.getMessage() == null) {
            return;
        }

        String notificationId = queuedNotification.getMessage().notificationId();
        if (notificationId == null || notificationId.isBlank()) {
            return;
        }

        QueuePayload payload = new QueuePayload(
                queuedNotification.getMessage(),
                queuedNotification.getAttempts(),
                queuedNotification.getNextAttemptAt()
        );

        try {
            redisTemplate.opsForValue().set(payloadKey(notificationId), objectMapper.writeValueAsString(payload));
            redisTemplate.opsForZSet().add(readyQueueKey, notificationId, queuedNotification.getNextAttemptAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize queued notification", exception);
        }
    }

    public List<QueuedNotification> drainReady(int maxBatchSize) {
        long now = System.currentTimeMillis();
        List<QueuedNotification> batch = new ArrayList<>(maxBatchSize);

        Set<String> candidateIds = redisTemplate.opsForZSet().rangeByScore(readyQueueKey, 0, now, 0, maxBatchSize);
        if (candidateIds == null || candidateIds.isEmpty()) {
            return batch;
        }

        for (String notificationId : candidateIds) {
            Long removed = redisTemplate.opsForZSet().remove(readyQueueKey, notificationId);
            if (removed == null || removed == 0) {
                continue;
            }

            String payloadJson = redisTemplate.opsForValue().get(payloadKey(notificationId));
            redisTemplate.delete(payloadKey(notificationId));
            if (payloadJson == null || payloadJson.isBlank()) {
                continue;
            }

            try {
                QueuePayload payload = objectMapper.readValue(payloadJson, QueuePayload.class);
                batch.add(new QueuedNotification(payload.getMessage(), payload.getAttempts(), payload.getNextAttemptAt()));
            } catch (JsonProcessingException exception) {
                // Drop malformed payload to avoid poison loops.
                moveToDeadLetter(null);
            }
        }

        return batch;
    }

    public void moveToDeadLetter(QueuedNotification ignoredNotification) {
        redisTemplate.opsForValue().increment(deadLetterCounterKey);
    }

    public int pendingCount() {
        Long size = redisTemplate.opsForZSet().size(readyQueueKey);
        return size == null ? 0 : Math.toIntExact(size);
    }

    public int deadLetterCount() {
        String count = redisTemplate.opsForValue().get(deadLetterCounterKey);
        if (count == null || count.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(count);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String payloadKey(String notificationId) {
        return payloadKeyPrefix + notificationId;
    }

    private static class QueuePayload {
        private com.epam.notifications.domain.NotificationMessage message;
        private int attempts;
        private long nextAttemptAt;

        public QueuePayload() {
        }

        public QueuePayload(com.epam.notifications.domain.NotificationMessage message, int attempts, long nextAttemptAt) {
            this.message = message;
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
        }

        public com.epam.notifications.domain.NotificationMessage getMessage() {
            return message;
        }

        public void setMessage(com.epam.notifications.domain.NotificationMessage message) {
            this.message = message;
        }

        public int getAttempts() {
            return attempts;
        }

        public void setAttempts(int attempts) {
            this.attempts = attempts;
        }

        public long getNextAttemptAt() {
            return nextAttemptAt;
        }

        public void setNextAttemptAt(long nextAttemptAt) {
            this.nextAttemptAt = nextAttemptAt;
        }
    }
}
