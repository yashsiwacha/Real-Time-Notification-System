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
                        "createNotification", "POST /api/notifications (requires ROLE_ADMIN via JWT, API key, or session)",
                        "stats", "GET /api/notifications/system-stats (requires ROLE_ADMIN or ROLE_USER via JWT, or session)",
                        "overview", "GET /api/notifications/overview (requires ROLE_ADMIN or ROLE_USER)",
                        "recentEvents", "GET /api/notifications/recent-events?limit=25 (requires ROLE_ADMIN or ROLE_USER)",
                        "failures", "GET /api/notifications/failures?limit=25 (requires ROLE_ADMIN or ROLE_USER)",
                        "authLogin", "POST /api/auth/login (returns JWT)",
                        "sessionLogin", "POST /api/session/login (one-time API key for cookie session)"
                )
        );
    }
}
