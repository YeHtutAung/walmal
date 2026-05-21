package com.walmal.order.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request DTO for a single line item in a {@link CreateOrderRequest}.
 */
public record OrderLineItemRequest(
        @NotNull UUID variantId,
        @NotNull UUID locationId,
        @Min(1) int quantity
) {}
