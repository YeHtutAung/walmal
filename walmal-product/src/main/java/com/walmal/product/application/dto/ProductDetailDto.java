package com.walmal.product.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Richer product projection — used by Order confirmation and POS receipt display.
 */
public record ProductDetailDto(
        UUID productId,
        String name,
        String slug,
        String brand,
        String description,
        String status,
        UUID categoryId,
        String categoryName,
        String primaryImageUrl,
        BigDecimal lowestPrice,
        String currency
) {}
