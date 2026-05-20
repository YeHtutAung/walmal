package com.walmal.inventory.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Request body for transferring stock between locations.
 */
public record TransferStockRequest(
        @NotNull UUID variantId,
        @NotNull UUID fromLocationId,
        @NotNull UUID toLocationId,
        @Positive int quantity
) {}
