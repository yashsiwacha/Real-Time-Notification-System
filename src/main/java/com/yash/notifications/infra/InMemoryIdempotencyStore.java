package com.yash.notifications.infra;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryIdempotencyStore {

    private final ConcurrentMap<String, String> keyToNotificationId = new ConcurrentHashMap<>();

    public Optional<String> findExisting(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(keyToNotificationId.get(key));
    }

    public String remember(String key, String notificationId) {
        if (key == null || key.isBlank()) {
            return notificationId;
        }
        return keyToNotificationId.computeIfAbsent(key, ignored -> notificationId);
    }
}
