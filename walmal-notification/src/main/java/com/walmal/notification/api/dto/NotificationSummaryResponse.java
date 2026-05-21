package com.walmal.notification.api.dto;

import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationSummaryResponse(
        UUID id,
        NotificationType type,
        NotificationStatus status,
        String subject,
        String triggerEvent,
        UUID referenceId,
        Instant createdAt
) {}
