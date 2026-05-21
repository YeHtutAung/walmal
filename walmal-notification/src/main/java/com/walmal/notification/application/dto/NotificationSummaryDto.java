package com.walmal.notification.application.dto;

import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationSummaryDto(
        UUID id,
        UUID recipientId,
        NotificationType type,
        NotificationStatus status,
        String subject,
        String triggerEvent,
        UUID referenceId,
        Instant createdAt
) {}
