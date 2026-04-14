CREATE UNIQUE INDEX ux_notifications_user_idempotency
ON notifications(user_id, idempotency_key);
