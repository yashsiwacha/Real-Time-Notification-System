package com.epam.notifications.persistence;

public enum NotificationDeliveryStatus {
    ENQUEUED,
    RETRY_SCHEDULED,
    DELIVERED,
    DEAD_LETTER
}
