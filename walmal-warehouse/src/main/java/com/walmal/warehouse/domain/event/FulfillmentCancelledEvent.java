package com.walmal.warehouse.domain.event;

import com.walmal.common.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a fulfillment is cancelled (order cancelled before shipping).
 * Routing key: {@code warehouse.fulfillment.cancelled}
 *
 * <p>Notification module uses this to send "Your fulfillment was cancelled" email.</p>
 */
public class FulfillmentCancelledEvent extends DomainEvent {

    private final UUID orderId;
    private final UUID userId;
    private final String reason;
    private final Instant cancelledAt;

    public FulfillmentCancelledEvent(UUID orderId, UUID userId, String reason) {
        super("warehouse.fulfillment.cancelled");
        this.orderId = orderId;
        this.userId = userId;
        this.reason = reason;
        this.cancelledAt = Instant.now();
    }

    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public String getReason() { return reason; }
    public Instant getCancelledAt() { return cancelledAt; }
}
