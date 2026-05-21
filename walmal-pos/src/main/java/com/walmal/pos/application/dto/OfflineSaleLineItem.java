package com.walmal.pos.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single line item within an offline sale payload submitted by a POS terminal.
 *
 * <p>For offline sales the device sends price and product name snapshots because it was
 * disconnected when prices were fetched. The server validates that the variant is still
 * active but trusts the device-provided price and name for MVP.</p>
 *
 * @param variantId           the product variant UUID
 * @param locationId          the inventory location UUID (POS terminal's store location)
 * @param quantity            units sold
 * @param priceAtSale         unit price at the time of the offline sale (device snapshot)
 * @param currency            ISO-4217 currency code
 * @param productNameSnapshot product name as displayed on the POS device at sale time
 * @param skuSnapshot         SKU as displayed on the POS device at sale time
 */
public record OfflineSaleLineItem(
        UUID variantId,
        UUID locationId,
        int quantity,
        BigDecimal priceAtSale,
        String currency,
        String productNameSnapshot,
        String skuSnapshot
) {}
