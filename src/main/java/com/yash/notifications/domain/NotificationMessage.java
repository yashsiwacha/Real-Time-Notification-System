package com.yash.notifications.domain;

import java.time.Instant;
import java.util.Map;

public record NotificationMessage(
        String notificationId,
        String userId,
        String type,
        String message,
        Map<String, String> metadata,
        Instant createdAt
) {
}
