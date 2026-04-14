package com.yash.notifications.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yash.notifications.domain.QueuedNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NotificationQueueService {

    private static final Logger log = LoggerFactory.getLogger(NotificationQueueService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String readyQueueKey;
    private final String payloadKeyPrefix;
    private final String deadLetterCounterKey;
    private final ConcurrentMap<String, QueuePayload> fallbackPayloads = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<MemoryReadyItem> fallbackReadyItems =
            new PriorityBlockingQueue<>(64, Comparator.comparingLong(item -> item.readyAt));
    private final AtomicInteger fallbackDeadLetterCount = new AtomicInteger(0);

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
        } catch (RuntimeException exception) {
            log.debug("Redis unavailable for enqueue, using in-memory fallback: {}", exception.getMessage());
            enqueueFallback(notificationId, payload);
        }
    }

    public List<QueuedNotification> drainReady(int maxBatchSize) {
        long now = System.currentTimeMillis();
        List<QueuedNotification> batch = new ArrayList<>(maxBatchSize);

        try {
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
        } catch (RuntimeException exception) {
            log.debug("Redis unavailable for drainReady, using in-memory fallback: {}", exception.getMessage());
            return drainReadyFallback(maxBatchSize, now);
        }
    }

    public void moveToDeadLetter(QueuedNotification ignoredNotification) {
        try {
            redisTemplate.opsForValue().increment(deadLetterCounterKey);
        } catch (RuntimeException exception) {
            fallbackDeadLetterCount.incrementAndGet();
        }
    }

    public int pendingCount() {
        try {
            Long size = redisTemplate.opsForZSet().size(readyQueueKey);
            return size == null ? 0 : Math.toIntExact(size);
        } catch (RuntimeException exception) {
            return fallbackPayloads.size();
        }
    }

    public int deadLetterCount() {
        try {
            String count = redisTemplate.opsForValue().get(deadLetterCounterKey);
            if (count == null || count.isBlank()) {
                return 0;
            }
            return Integer.parseInt(count);
        } catch (RuntimeException exception) {
            return fallbackDeadLetterCount.get();
        }
    }

    private void enqueueFallback(String notificationId, QueuePayload payload) {
        fallbackPayloads.put(notificationId, payload);
        fallbackReadyItems.offer(new MemoryReadyItem(notificationId, payload.getNextAttemptAt()));
    }

    private List<QueuedNotification> drainReadyFallback(int maxBatchSize, long nowMs) {
        List<QueuedNotification> batch = new ArrayList<>(maxBatchSize);
        while (batch.size() < maxBatchSize) {
            MemoryReadyItem next = fallbackReadyItems.peek();
            if (next == null || next.readyAt > nowMs) {
                break;
            }

            fallbackReadyItems.poll();
            QueuePayload payload = fallbackPayloads.remove(next.notificationId);
            if (payload == null) {
                continue;
            }

            batch.add(new QueuedNotification(payload.getMessage(), payload.getAttempts(), payload.getNextAttemptAt()));
        }
        return batch;
    }

    private String payloadKey(String notificationId) {
        return payloadKeyPrefix + notificationId;
    }

    private static final class MemoryReadyItem {
        private final String notificationId;
        private final long readyAt;

        private MemoryReadyItem(String notificationId, long readyAt) {
            this.notificationId = notificationId;
            this.readyAt = readyAt;
        }
    }

    private static class QueuePayload {
        private com.yash.notifications.domain.NotificationMessage message;
        private int attempts;
        private long nextAttemptAt;

        public QueuePayload() {
        }

        public QueuePayload(com.yash.notifications.domain.NotificationMessage message, int attempts, long nextAttemptAt) {
            this.message = message;
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
        }

        public com.yash.notifications.domain.NotificationMessage getMessage() {
            return message;
        }

        public void setMessage(com.yash.notifications.domain.NotificationMessage message) {
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