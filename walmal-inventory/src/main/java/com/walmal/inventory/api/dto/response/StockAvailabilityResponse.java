package com.walmal.inventory.api.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Aggregated availability across all active locations for a variant.
 * Includes a per-location breakdown for the POS stock-check screen.
 */
public record StockAvailabilityResponse(
        UUID variantId,
        int totalAvailable,
        int totalReserved,
        boolean available,
        List<StockLevelResponse> locations
) {}
