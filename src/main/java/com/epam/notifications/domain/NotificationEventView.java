package com.epam.notifications.domain;

import com.epam.notifications.persistence.NotificationDeliveryStatus;

import java.time.Instant;

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
