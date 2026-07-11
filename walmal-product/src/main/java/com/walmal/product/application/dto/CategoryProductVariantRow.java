package com.walmal.product.application.dto;

import java.util.UUID;

/**
 * Flat projection row for the category-level stock-health rollup (consumed by walmal-inventory
 * via ProductCatalogService). productId/variantId are nullable — a category can have zero
 * products, and a product can have zero variants; both must still appear here (LEFT JOIN, not
 * INNER JOIN) so callers can count them correctly.
 */
public record CategoryProductVariantRow(UUID categoryId, String categoryName, UUID productId, UUID variantId) {}
