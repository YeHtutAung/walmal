package com.walmal.order.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Line item projection included in {@link OrderDetailDto}.
 */
public record OrderItemDto(
        UUID variantId,
        String productNameSnapshot,
        String skuSnapshot,
        int quantity,
        BigDecimal priceAtPurchase,
        String currency,
        BigDecimal subtotal
) {}
