package com.walmal.warehouse.application.dto;

import com.walmal.warehouse.domain.FulfillmentStatus;

import java.time.Instant;
import java.util.UUID;

public record FulfillmentSummaryDto(
        UUID id,
        UUID orderId,
        UUID userId,
        FulfillmentStatus status,
        int lineCount,
        Instant createdAt,
        Instant updatedAt
) {}
