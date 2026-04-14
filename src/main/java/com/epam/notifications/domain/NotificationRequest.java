package com.epam.notifications.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record NotificationRequest(
        @NotBlank(message = "userId is required")
        @Size(max = 128, message = "userId must be at most 128 characters")
        String userId,
        @NotBlank(message = "type is required")
        @Size(max = 64, message = "type must be at most 64 characters")
        String type,
        @NotBlank(message = "message is required")
        @Size(max = 1024, message = "message must be at most 1024 characters")
        String message,
        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 256, message = "idempotencyKey must be at most 256 characters")
        String idempotencyKey,
        Map<String, String> metadata
) {
}
