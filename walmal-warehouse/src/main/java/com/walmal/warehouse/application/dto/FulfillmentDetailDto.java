package com.walmal.warehouse.application.dto;

import com.walmal.order.domain.OrderStatus;
import com.walmal.warehouse.domain.FulfillmentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FulfillmentDetailDto(
        UUID id,
        UUID orderId,
        UUID userId,
        FulfillmentStatus status,
        OrderStatus orderStatus,
        String shippingAddress,
        List<FulfillmentLineDto> lines,
        ShipmentDto shipment,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {}
