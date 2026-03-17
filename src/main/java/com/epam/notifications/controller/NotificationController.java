package com.epam.notifications.controller;

import com.epam.notifications.domain.NotificationRequest;
import com.epam.notifications.domain.NotificationStatusResponse;
import com.epam.notifications.infra.TokenBucketRateLimiter;
import com.epam.notifications.service.ConnectedUserRegistry;
import com.epam.notifications.service.NotificationPersistenceService;
import com.epam.notifications.service.NotificationProducerService;
import com.epam.notifications.service.NotificationQueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationProducerService notificationProducerService;
    private final NotificationQueueService notificationQueueService;
    private final NotificationPersistenceService notificationPersistenceService;
    private final ConnectedUserRegistry connectedUserRegistry;
    private final TokenBucketRateLimiter tokenBucketRateLimiter;

    public NotificationController(NotificationProducerService notificationProducerService,
                                  NotificationQueueService notificationQueueService,
                                  NotificationPersistenceService notificationPersistenceService,
                                  ConnectedUserRegistry connectedUserRegistry,
                                  TokenBucketRateLimiter tokenBucketRateLimiter) {
        this.notificationProducerService = notificationProducerService;
        this.notificationQueueService = notificationQueueService;
        this.notificationPersistenceService = notificationPersistenceService;
        this.connectedUserRegistry = connectedUserRegistry;
        this.tokenBucketRateLimiter = tokenBucketRateLimiter;
    }

    @PostMapping
    public ResponseEntity<?> createNotification(@Valid @RequestBody NotificationRequest request) {
        String limiterKey = "notify:" + request.userId();
        TokenBucketRateLimiter.Decision decision = tokenBucketRateLimiter.consume(limiterKey, 1);
        if (!decision.allowed()) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(decision.retryAfterSeconds()))
                    .body(Map.of(
                            "error", "rate_limit_exceeded",
                            "retryAfterSeconds", decision.retryAfterSeconds()
                    ));
        }

        NotificationStatusResponse status = notificationProducerService.produce(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    @GetMapping("/system-stats")
    public Map<String, Integer> systemStats() {
        return Map.of(
                "pendingQueue", notificationQueueService.pendingCount(),
                "deadLetter", Math.toIntExact(notificationPersistenceService.deadLetterCount()),
                "delivered", Math.toIntExact(notificationPersistenceService.deliveredCount()),
                "connectedUsers", connectedUserRegistry.connectedCount()
        );
    }
}
