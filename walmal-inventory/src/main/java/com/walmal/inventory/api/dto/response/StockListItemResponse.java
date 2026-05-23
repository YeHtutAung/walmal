package com.walmal.inventory.api.dto.response;

import java.util.UUID;

/**
 * Paginated stock-level row for the admin inventory list view.
 * Includes location name (intra-module join) but NOT product name or SKU —
 * those belong to the product module and must not be joined here.
 */
public record StockListItemResponse(
        UUID stockId,
        UUID variantId,
        UUID locationId,
        String locationName,
        int availableQuantity,
        int reservedQuantity,
        int lowStockThreshold
) {
    public int totalQuantity() {
        return availableQuantity + reservedQuantity;
    }
}
