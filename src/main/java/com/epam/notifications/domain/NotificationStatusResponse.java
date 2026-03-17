package com.epam.notifications.domain;

public record NotificationStatusResponse(
        String notificationId,
        boolean duplicate,
        String status
) {
}
