package com.walmal.inventory.application;

import com.walmal.inventory.api.dto.response.StockAvailabilityResponse;
import com.walmal.inventory.api.dto.response.StockLevelResponse;

import java.util.UUID;

/**
 * Cross-module service interface — consumed by the POS module.
 *
 * <p>ISP: POS needs real-time stock reads for barcode scan flow. It does not need
 * reservation lifecycle management or adjustment operations.</p>
 *
 * <p>All methods are read-only and cacheable via CacheService.</p>
 */
public interface InventoryQueryService {

    /**
     * Returns stock level for a specific variant at a specific location.
     * Cached at key {@code inventory:stock:{variantId}:{locationId}}, TTL 30 seconds.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if no stock record exists
     */
    StockLevelResponse getStockLevel(UUID variantId, UUID locationId);

    /**
     * Returns true if available_quantity >= requested quantity at any active location.
     * Aggregates across all non-buffer locations for the variant.
     * Cached at key {@code inventory:availability:{variantId}}, TTL 60 seconds.
     *
     * @param variantId the variant UUID
     * @param quantity  the units the caller intends to sell
     */
    boolean checkAvailability(UUID variantId, int quantity);

    /**
     * Returns aggregated availability across all active locations for the given variant,
     * including a per-location breakdown. Supports the POS stock-check screen.
     */
    StockAvailabilityResponse getAggregatedAvailability(UUID variantId);
}
