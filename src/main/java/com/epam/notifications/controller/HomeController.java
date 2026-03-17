package com.epam.notifications.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> home() {
        return Map.of(
                "service", "real-time-notification-system",
                "status", "running",
                "publicEndpoints", Map.of(
                        "demo", "/demo",
                        "health", "/actuator/health",
                        "info", "/actuator/info"
                ),
                "securedEndpoints", Map.of(
                        "createNotification", "POST /api/notifications (requires X-API-KEY)",
                        "stats", "GET /api/notifications/system-stats (requires X-API-KEY)"
                )
        );
    }
}
