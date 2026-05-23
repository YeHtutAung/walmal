package com.walmal.notification.infrastructure;

import com.walmal.notification.domain.NotificationLog;
import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    Page<NotificationLog> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

    long countByRecipientIdAndStatus(UUID recipientId, NotificationStatus status);

    @Query("SELECT n FROM NotificationLog n " +
           "WHERE (:type IS NULL OR n.type = :type) " +
           "AND (:status IS NULL OR n.status = :status)")
    Page<NotificationLog> findByFilters(@Param("type") @Nullable NotificationType type,
                                        @Param("status") @Nullable NotificationStatus status,
                                        Pageable pageable);
}
