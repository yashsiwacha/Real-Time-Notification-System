package com.epam.notifications.domain;

public record NotificationOverviewResponse(
        long totalEvents,
        long enqueued,
        long retryScheduled,
        long delivered,
        long deadLetter,
        double successRatePercent,
        double failureRatePercent,
        double throughputPerSecondLastFiveMinutes
) {
}
