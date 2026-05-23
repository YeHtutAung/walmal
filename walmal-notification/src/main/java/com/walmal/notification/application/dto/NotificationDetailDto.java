package com.walmal.notification.application.dto;

import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationDetailDto(
        UUID id,
        UUID recipientId,
        NotificationType channel,
        NotificationStatus status,
        String subject,
        String body,
        String errorMessage,
        String triggerEvent,
        UUID referenceId,
        Instant createdAt,
        Instant updatedAt
) {}
