package com.epam.notifications.domain;

public class QueuedNotification implements Comparable<QueuedNotification> {

    private final NotificationMessage message;
    private int attempts;
    private long nextAttemptAt;

    public QueuedNotification(NotificationMessage message, int attempts, long nextAttemptAt) {
        this.message = message;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
    }

    public static QueuedNotification fresh(NotificationMessage message) {
        return new QueuedNotification(message, 0, System.currentTimeMillis());
    }

    public NotificationMessage getMessage() {
        return message;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void markFailedAndScheduleRetry(long backoffMs) {
        this.attempts += 1;
        this.nextAttemptAt = System.currentTimeMillis() + backoffMs;
    }

    @Override
    public int compareTo(QueuedNotification other) {
        return Long.compare(this.nextAttemptAt, other.nextAttemptAt);
    }
}
