package com.walmal.order.application.dto;

import com.walmal.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight order projection for the admin list view. Includes item count.
 */
public record OrderAdminSummaryDto(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        int itemCount,
        Instant createdAt
) {}
