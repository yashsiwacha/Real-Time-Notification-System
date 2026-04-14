DELETE FROM notifications n
USING (
	SELECT notification_id
	FROM (
		SELECT notification_id,
			   ROW_NUMBER() OVER (
				   PARTITION BY user_id, idempotency_key
				   ORDER BY updated_at DESC, created_at DESC, notification_id DESC
			   ) AS row_num
		FROM notifications
		WHERE idempotency_key IS NOT NULL
	) ranked
	WHERE row_num > 1
) duplicates
WHERE n.notification_id = duplicates.notification_id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_notifications_user_idempotency
ON notifications(user_id, idempotency_key)
WHERE idempotency_key IS NOT NULL;
