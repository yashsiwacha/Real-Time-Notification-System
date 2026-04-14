package com.epam.notifications.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DeliveryCircuitBreaker {

    private final int failureThreshold;
    private final long openDurationMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openUntilEpochMs = new AtomicLong(0);

    public DeliveryCircuitBreaker(
            @Value("${notification.dispatcher.circuit-breaker.failure-threshold:20}") int failureThreshold,
            @Value("${notification.dispatcher.circuit-breaker.open-duration-ms:10000}") long openDurationMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMs = Math.max(1000, openDurationMs);
    }

    public boolean allowRequest() {
        return System.currentTimeMillis() >= openUntilEpochMs.get();
    }

    public long remainingOpenMs() {
        long remaining = openUntilEpochMs.get() - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilEpochMs.set(0);
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures < failureThreshold) {
            return;
        }

        long openUntil = System.currentTimeMillis() + openDurationMs;
        openUntilEpochMs.accumulateAndGet(openUntil, Math::max);
        consecutiveFailures.set(0);
    }
}
