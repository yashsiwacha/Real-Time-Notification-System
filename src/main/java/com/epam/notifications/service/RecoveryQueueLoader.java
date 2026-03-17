package com.epam.notifications.service;

import com.epam.notifications.domain.NotificationMessage;
import com.epam.notifications.domain.QueuedNotification;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RecoveryQueueLoader {

    private final NotificationPersistenceService notificationPersistenceService;
    private final NotificationQueueService notificationQueueService;
    private final int recoveryBatchSize;

    public RecoveryQueueLoader(NotificationPersistenceService notificationPersistenceService,
                               NotificationQueueService notificationQueueService,
                               @Value("${notification.dispatcher.recovery-batch-size:500}") int recoveryBatchSize) {
        this.notificationPersistenceService = notificationPersistenceService;
        this.notificationQueueService = notificationQueueService;
        this.recoveryBatchSize = recoveryBatchSize;
    }

    @PostConstruct
    public void loadRecoverableNotifications() {
        for (NotificationMessage message : notificationPersistenceService.loadRecoverable(recoveryBatchSize)) {
            notificationQueueService.enqueue(QueuedNotification.fresh(message));
        }
    }
}
