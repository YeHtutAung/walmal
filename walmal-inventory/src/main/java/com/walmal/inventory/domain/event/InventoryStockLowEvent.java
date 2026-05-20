package com.walmal.inventory.domain.event;

import com.walmal.common.event.DomainEvent;

import java.util.UUID;

/**
 * Published when available_quantity falls to or below low_stock_threshold after any mutation.
 * Routing key: {@code inventory.stock.low}
 */
public class InventoryStockLowEvent extends DomainEvent {

    private final UUID variantId;
    private final UUID locationId;
    private final int availableQuantity;
    private final int threshold;

    public InventoryStockLowEvent(UUID variantId, UUID locationId,
                                   int availableQuantity, int threshold) {
        super("inventory.stock.low");
        this.variantId = variantId;
        this.locationId = locationId;
        this.availableQuantity = availableQuantity;
        this.threshold = threshold;
    }

    public UUID getVariantId() { return variantId; }
    public UUID getLocationId() { return locationId; }
    public int getAvailableQuantity() { return availableQuantity; }
    public int getThreshold() { return threshold; }
}
