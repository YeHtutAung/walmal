package com.walmal.inventory.domain.event;

import com.walmal.common.event.DomainEvent;
import com.walmal.inventory.domain.ConflictReason;

import java.util.UUID;

/**
 * Published when reservations are released (cancel, expiry, or POS conflict).
 * Routing key: {@code inventory.reservation.released}
 *
 * <p>The {@code conflictReason} field allows downstream consumers (Notification module)
 * to compose the appropriate customer message.</p>
 */
public class InventoryReservationReleasedEvent extends DomainEvent {

    private final UUID orderId;
    private final UUID variantId;
    private final UUID locationId;
    private final int quantity;
    private final ConflictReason conflictReason;

    public InventoryReservationReleasedEvent(UUID orderId, UUID variantId,
                                              UUID locationId, int quantity,
                                              ConflictReason conflictReason) {
        super("inventory.reservation.released");
        this.orderId = orderId;
        this.variantId = variantId;
        this.locationId = locationId;
        this.quantity = quantity;
        this.conflictReason = conflictReason;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getVariantId() { return variantId; }
    public UUID getLocationId() { return locationId; }
    public int getQuantity() { return quantity; }
    public ConflictReason getConflictReason() { return conflictReason; }
}
