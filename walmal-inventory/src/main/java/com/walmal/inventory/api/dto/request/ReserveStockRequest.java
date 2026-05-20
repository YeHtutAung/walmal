package com.walmal.inventory.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for reserving stock across multiple line items.
 */
public record ReserveStockRequest(
        @NotNull UUID orderId,
        @NotNull @Size(min = 1) @Valid List<LineItem> items
) {
    public record LineItem(
            @NotNull UUID variantId,
            @NotNull UUID locationId,
            @Positive int quantity
    ) {}
}
