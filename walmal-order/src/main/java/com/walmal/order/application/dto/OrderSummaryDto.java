package com.walmal.order.application.dto;

import com.walmal.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight order projection used in paginated list responses.
 */
public record OrderSummaryDto(
        UUID id,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt
) {}
