package com.epam.notifications.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record NotificationRequest(
        @NotBlank(message = "userId is required") String userId,
        @NotBlank(message = "type is required") String type,
        @NotBlank(message = "message is required") String message,
        String idempotencyKey,
        Map<String, String> metadata
) {
}
