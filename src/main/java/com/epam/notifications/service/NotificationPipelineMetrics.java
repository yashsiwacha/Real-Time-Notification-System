package com.epam.notifications.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NotificationPipelineMetrics {

    private final AtomicInteger pendingApprox = new AtomicInteger(0);
    private final Counter deliveredCounter;
    private final Counter deadLetterCounter;
    private final Counter retryCounter;
    private final Timer deliveryLatency;

    public NotificationPipelineMetrics(MeterRegistry meterRegistry) {
        this.deliveredCounter = Counter.builder("notification_delivery_total")
                .description("Total successfully delivered notifications")
                .register(meterRegistry);

        this.deadLetterCounter = Counter.builder("notification_dead_letter_total")
                .description("Total dead-letter notifications")
                .register(meterRegistry);

        this.retryCounter = Counter.builder("notification_retry_total")
                .description("Total delivery retry attempts")
                .register(meterRegistry);

        this.deliveryLatency = Timer.builder("notification_delivery_latency")
                .description("End-to-end notification delivery latency")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(meterRegistry);

        Gauge.builder("notification_queue_pending_approx", pendingApprox, AtomicInteger::get)
                .description("Approximate number of pending notifications")
                .register(meterRegistry);
    }

    public void onEnqueued() {
        pendingApprox.incrementAndGet();
    }

    public void onDelivered(Instant createdAt) {
        pendingApprox.updateAndGet(value -> Math.max(0, value - 1));
        deliveredCounter.increment();
        if (createdAt != null) {
            deliveryLatency.record(Duration.between(createdAt, Instant.now()));
        }
    }

    public void onDeadLetter() {
        pendingApprox.updateAndGet(value -> Math.max(0, value - 1));
        deadLetterCounter.increment();
    }

    public void onRetryAttempt() {
        retryCounter.increment();
    }

    public int pendingApprox() {
        return pendingApprox.get();
    }
}
