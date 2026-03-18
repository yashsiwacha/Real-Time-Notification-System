package com.epam.notifications.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("""
                        update NotificationEntity n
                        set n.status = :status,
                                n.deliveredAt = :deliveredAt,
                                n.updatedAt = :updatedAt
                        where n.notificationId = :notificationId
                        """)
        int updateDelivered(@Param("notificationId") String notificationId,
                                                @Param("status") NotificationDeliveryStatus status,
                                                @Param("deliveredAt") Instant deliveredAt,
                                                @Param("updatedAt") Instant updatedAt);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("""
                        update NotificationEntity n
                        set n.status = :status,
                                n.attempts = :attempts,
                                n.nextAttemptAt = :nextAttemptAt,
                                n.failedReason = :failedReason,
                                n.updatedAt = :updatedAt
                        where n.notificationId = :notificationId
                        """)
        int updateRetryScheduled(@Param("notificationId") String notificationId,
                                                         @Param("status") NotificationDeliveryStatus status,
                                                         @Param("attempts") int attempts,
                                                         @Param("nextAttemptAt") Instant nextAttemptAt,
                                                         @Param("failedReason") String failedReason,
                                                         @Param("updatedAt") Instant updatedAt);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("""
                        update NotificationEntity n
                        set n.status = :status,
                                n.attempts = :attempts,
                                n.failedReason = :failedReason,
                                n.updatedAt = :updatedAt
                        where n.notificationId = :notificationId
                        """)
        int updateDeadLetter(@Param("notificationId") String notificationId,
                                                 @Param("status") NotificationDeliveryStatus status,
                                                 @Param("attempts") int attempts,
                                                 @Param("failedReason") String failedReason,
                                                 @Param("updatedAt") Instant updatedAt);
}
