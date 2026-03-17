CREATE TABLE notifications (
    notification_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    type VARCHAR(64) NOT NULL,
    message VARCHAR(1024) NOT NULL,
    metadata_json VARCHAR(4000) NOT NULL,
    idempotency_key VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    delivered_at TIMESTAMP NULL,
    failed_reason VARCHAR(512) NULL
);

CREATE INDEX idx_notifications_status_next_attempt
ON notifications(status, next_attempt_at);

CREATE INDEX idx_notifications_user_idempotency
ON notifications(user_id, idempotency_key);
