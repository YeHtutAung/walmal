package com.walmal.inventory.api.dto.response;

import java.util.UUID;

/**
 * Category-level stock-health rollup: how many products in the category fall into each
 * {@link com.walmal.inventory.domain.StockHealthStatus} bucket.
 *
 * <p>{@code productCount} counts distinct products in the category (including variant-less
 * products); {@code okCount}/{@code lowCount}/{@code criticalCount} tally per-variant stock
 * rows classified via {@link com.walmal.inventory.domain.InventoryStock#classifyHealth()} — a
 * product with multiple variants or multiple stock locations can contribute more than one
 * count, and the three counts need not sum to {@code productCount}.</p>
 */
public record CategoryStockHealthDto(
        UUID categoryId, String categoryName, long productCount,
        long okCount, long lowCount, long criticalCount
) {}
