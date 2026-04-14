package com.epam.notifications.controller;

import com.epam.notifications.domain.NotificationRequest;
import com.epam.notifications.domain.NotificationStatusResponse;
import com.epam.notifications.domain.NotificationEventView;
import com.epam.notifications.domain.NotificationOverviewResponse;
import com.epam.notifications.infra.TokenBucketRateLimiter;
import com.epam.notifications.service.ConnectedUserRegistry;
import com.epam.notifications.service.NotificationPipelineMetrics;
import com.epam.notifications.service.NotificationPersistenceService;
import com.epam.notifications.service.NotificationProducerService;
import com.epam.notifications.service.NotificationQueueService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationProducerService notificationProducerService;
    private final NotificationQueueService notificationQueueService;
    private final NotificationPersistenceService notificationPersistenceService;
    private final ConnectedUserRegistry connectedUserRegistry;
    private final TokenBucketRateLimiter tokenBucketRateLimiter;
    private final NotificationPipelineMetrics notificationPipelineMetrics;
    private final boolean kafkaEnabled;
    private final int maxPendingBeforeBackpressure;

    public NotificationController(NotificationProducerService notificationProducerService,
                                  NotificationQueueService notificationQueueService,
                                  NotificationPersistenceService notificationPersistenceService,
                                  ConnectedUserRegistry connectedUserRegistry,
                                  TokenBucketRateLimiter tokenBucketRateLimiter,
                                  NotificationPipelineMetrics notificationPipelineMetrics,
                                  @Value("${notification.kafka.enabled:false}") boolean kafkaEnabled,
                                  @Value("${notification.backpressure.max-pending:50000}") int maxPendingBeforeBackpressure) {
        this.notificationProducerService = notificationProducerService;
        this.notificationQueueService = notificationQueueService;
        this.notificationPersistenceService = notificationPersistenceService;
        this.connectedUserRegistry = connectedUserRegistry;
        this.tokenBucketRateLimiter = tokenBucketRateLimiter;
        this.notificationPipelineMetrics = notificationPipelineMetrics;
        this.kafkaEnabled = kafkaEnabled;
        this.maxPendingBeforeBackpressure = maxPendingBeforeBackpressure;
    }

    @PostMapping
    public ResponseEntity<?> createNotification(@Valid @RequestBody NotificationRequest request,
                                                Authentication authentication) {
        String limiterKey = buildLimiterKey(authentication, request.userId());
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

        int pending = kafkaEnabled
                ? notificationPipelineMetrics.pendingApprox()
                : notificationQueueService.pendingCount();
        if (pending >= maxPendingBeforeBackpressure) {
            notificationPipelineMetrics.onBackpressureRejected();
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "backpressure_active",
                            "pendingQueue", pending,
                            "maxPending", maxPendingBeforeBackpressure
                    ));
        }

        NotificationStatusResponse status = notificationProducerService.produce(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    @GetMapping("/system-stats")
    public Map<String, Integer> systemStats() {
        int pending = kafkaEnabled
                ? notificationPipelineMetrics.pendingApprox()
                : notificationQueueService.pendingCount();

        return Map.of(
                "pendingQueue", pending,
                "deadLetter", Math.toIntExact(notificationPersistenceService.deadLetterCount()),
                "delivered", Math.toIntExact(notificationPersistenceService.deliveredCount()),
                "connectedUsers", connectedUserRegistry.connectedCount()
        );
    }

    @GetMapping("/overview")
    public NotificationOverviewResponse overview() {
        return notificationPersistenceService.overview();
    }

    @GetMapping("/recent-events")
    public List<NotificationEventView> recentEvents(@RequestParam(name = "limit", defaultValue = "25") int limit) {
        return notificationPersistenceService.recentEvents(limit);
    }

    @GetMapping("/failures")
    public List<NotificationEventView> failures(@RequestParam(name = "limit", defaultValue = "25") int limit) {
        return notificationPersistenceService.recentFailures(limit);
    }

    private String buildLimiterKey(Authentication authentication, String targetUserId) {
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return "notify:actor:" + authentication.getName();
        }
        return "notify:target:" + targetUserId;
    }
}
