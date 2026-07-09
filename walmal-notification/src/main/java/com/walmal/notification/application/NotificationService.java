package com.walmal.notification.application;

import com.walmal.notification.application.dto.NotificationSummaryDto;
import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationService {

    void sendNotification(UUID recipientId, NotificationType type, String subject,
                          String body, String triggerEvent, UUID referenceId);

    /**
     * Sends an EMAIL notification to a guest (no user account).
     * The log row is keyed by {@code recipientEmail}; {@code recipientId} stays null.
     */
    void sendGuestEmailNotification(String recipientEmail, String subject, String body,
                                    String triggerEvent, UUID referenceId);

    Page<NotificationSummaryDto> listNotificationsForUser(UUID userId, Pageable pageable);

    long countUnread(UUID userId);
}
