package com.walmal.product.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lightweight product projection used in search and browse results.
 * Consumed by the API Gateway layer.
 */
public record ProductSummaryDto(
        UUID productId,
        String name,
        String slug,
        String brand,
        String primaryImageUrl,
        BigDecimal lowestPrice,
        String currency
) {}
