package com.yash.notifications.persistence;

public enum NotificationDeliveryStatus {
    ENQUEUED,
    RETRY_SCHEDULED,
    DELIVERED,
    DEAD_LETTER
}
