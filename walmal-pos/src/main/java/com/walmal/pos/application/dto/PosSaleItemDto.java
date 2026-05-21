package com.walmal.pos.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read projection of a single POS sale line item.
 *
 * @param id                  line item UUID
 * @param variantId           cross-module ref to product variant
 * @param productNameSnapshot product name at time of sale (immutable snapshot)
 * @param skuSnapshot         SKU at time of sale (immutable snapshot)
 * @param quantity            units sold
 * @param priceAtSale         unit price at time of sale
 * @param currency            ISO-4217 currency code
 * @param subtotal            quantity * priceAtSale (computed at insert time)
 */
public record PosSaleItemDto(
        UUID id,
        UUID variantId,
        String productNameSnapshot,
        String skuSnapshot,
        int quantity,
        BigDecimal priceAtSale,
        String currency,
        BigDecimal subtotal
) {}
