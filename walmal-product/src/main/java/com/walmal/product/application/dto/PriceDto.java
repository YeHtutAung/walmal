package com.walmal.product.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Price projection used by cross-module consumers (Order, POS).
 */
public record PriceDto(
        UUID variantId,
        BigDecimal amount,
        String currency,
        Instant effectiveFrom
) {}
