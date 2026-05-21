package com.walmal.warehouse.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ShipmentDto(
        UUID id,
        String carrier,
        String trackingNumber,
        Instant shippedAt
) {}
