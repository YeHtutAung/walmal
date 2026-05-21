package com.walmal.order.application.dto;

import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.ShippingAddress;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full order projection used in single-order detail responses.
 */
public record OrderDetailDto(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        ShippingAddress shippingAddress,
        List<OrderItemDto> items,
        Instant createdAt
) {}
