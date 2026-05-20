package com.walmal.inventory.domain.event;

import com.walmal.common.event.DomainEvent;

import java.util.UUID;

/**
 * Published when all PENDING reservations for an order are confirmed (payment success).
 * Routing key: {@code inventory.reservation.confirmed}
 */
public class InventoryReservationConfirmedEvent extends DomainEvent {

    private final UUID orderId;
    private final UUID variantId;
    private final UUID locationId;
    private final int quantity;

    public InventoryReservationConfirmedEvent(UUID orderId, UUID variantId,
                                               UUID locationId, int quantity) {
        super("inventory.reservation.confirmed");
        this.orderId = orderId;
        this.variantId = variantId;
        this.locationId = locationId;
        this.quantity = quantity;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getVariantId() { return variantId; }
    public UUID getLocationId() { return locationId; }
    public int getQuantity() { return quantity; }
}
