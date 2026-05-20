package com.walmal.product.domain.event;

import com.walmal.common.event.DomainEvent;

import java.util.UUID;

/**
 * Published when a new product variant is created and activated.
 * Routing key: {@code product.created}
 * Consumer intent: Inventory module initialises a stock record for this variant.
 */
public class ProductCreatedEvent extends DomainEvent {

    private final UUID variantId;
    private final UUID productId;
    private final String sku;
    private final String productName;

    public ProductCreatedEvent(UUID variantId, UUID productId, String sku, String productName) {
        super("product.created");
        this.variantId = variantId;
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
    }

    public UUID getVariantId() { return variantId; }
    public UUID getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
}
