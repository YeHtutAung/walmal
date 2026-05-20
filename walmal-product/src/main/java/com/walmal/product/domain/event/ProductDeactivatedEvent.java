package com.walmal.product.domain.event;

import com.walmal.common.event.DomainEvent;

import java.util.UUID;

/**
 * Published when a product or variant is set INACTIVE.
 * Routing key: {@code product.deactivated}
 * Consumer intent: Inventory flags the variant unsellable; Order rejects pending lines for that variant.
 */
public class ProductDeactivatedEvent extends DomainEvent {

    /** The deactivated entity's id (product id or variant id depending on {@code entityType}). */
    private final UUID entityId;

    /** {@code "PRODUCT"} or {@code "VARIANT"}. */
    private final String entityType;

    /** SKU of the variant — present when entityType is VARIANT, null otherwise. */
    private final String sku;

    /** Always present — the parent product id. */
    private final UUID productId;

    private final String performedBy;

    public ProductDeactivatedEvent(UUID entityId, String entityType, String sku,
                                   UUID productId, String performedBy) {
        super("product.deactivated");
        this.entityId = entityId;
        this.entityType = entityType;
        this.sku = sku;
        this.productId = productId;
        this.performedBy = performedBy;
    }

    public UUID getEntityId() { return entityId; }
    public String getEntityType() { return entityType; }
    public String getSku() { return sku; }
    public UUID getProductId() { return productId; }
    public String getPerformedBy() { return performedBy; }
}
