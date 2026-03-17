package com.epam.notifications.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<NotificationEntity, String> {

    Optional<NotificationEntity> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    long countByStatus(NotificationDeliveryStatus status);

    @Query("""
            select n from NotificationEntity n
            where n.status in :statuses and n.nextAttemptAt <= :readyBefore
            order by n.nextAttemptAt asc
            """)
    List<NotificationEntity> findRecoverable(
            @Param("statuses") List<NotificationDeliveryStatus> statuses,
            @Param("readyBefore") Instant readyBefore,
            Pageable pageable
    );
}
