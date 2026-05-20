package com.walmal.inventory.domain.event;

import com.walmal.common.event.DomainEvent;

import java.util.UUID;

/**
 * Published when available_quantity reaches 0 after any stock mutation.
 * Routing key: {@code inventory.stock.exhausted}
 */
public class InventoryStockExhaustedEvent extends DomainEvent {

    private final UUID variantId;
    private final UUID locationId;

    public InventoryStockExhaustedEvent(UUID variantId, UUID locationId) {
        super("inventory.stock.exhausted");
        this.variantId = variantId;
        this.locationId = locationId;
    }

    public UUID getVariantId() { return variantId; }
    public UUID getLocationId() { return locationId; }
}
