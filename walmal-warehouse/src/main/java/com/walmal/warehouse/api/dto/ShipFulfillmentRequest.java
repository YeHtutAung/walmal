package com.walmal.warehouse.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ShipFulfillmentRequest(
        @NotBlank String carrier,
        @NotBlank String trackingNumber,
        String notes
) {}
