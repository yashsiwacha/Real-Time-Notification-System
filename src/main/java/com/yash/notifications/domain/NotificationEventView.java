package com.yash.notifications.domain;

import java.time.Instant;

import com.yash.notifications.persistence.NotificationDeliveryStatus;

public record NotificationEventView(
        String notificationId,
        String userId,
        String type,
        String message,
        NotificationDeliveryStatus status,
        int attempts,
        Instant createdAt,
        Instant updatedAt,
        Instant deliveredAt,
        String failedReason
) {
}
