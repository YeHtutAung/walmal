package com.walmal.inventory.api.dto.response;

import java.util.UUID;

/**
 * Stock level at a specific variant-location pair.
 * Used by InventoryQueryService and InventoryStockController.
 */
public record StockLevelResponse(
        UUID variantId,
        UUID locationId,
        String locationName,
        int availableQuantity,
        int reservedQuantity,
        int lowStockThreshold
) {}
