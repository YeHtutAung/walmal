package com.walmal.notification.infrastructure;

import com.walmal.notification.domain.NotificationTemplate;
import com.walmal.notification.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findByEventKeyAndTypeAndIsActiveTrue(String eventKey, NotificationType type);
}
