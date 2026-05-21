package com.walmal.pos.application.dto;

import java.util.UUID;

/**
 * A single line item for an online POS sale.
 *
 * <p>For online sales the server fetches product name, SKU, and price from
 * {@code ProductCatalogService} and {@code ProductPricingService} in real time.
 * The device does not need to send snapshot data.</p>
 *
 * @param variantId  the product variant UUID
 * @param locationId the inventory location UUID (POS terminal's store location)
 * @param quantity   units sold
 */
public record PosSaleLineItem(
        UUID variantId,
        UUID locationId,
        int quantity
) {}
