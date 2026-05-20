package com.walmal.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for a manual stock adjustment.
 */
public record AdjustStockRequest(
        @NotNull UUID variantId,
        @NotNull UUID locationId,
        int delta,
        @NotBlank @Size(max = 255) String reason
) {}
