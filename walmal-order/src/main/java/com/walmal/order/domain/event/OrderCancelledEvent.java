package com.walmal.order.domain.event;

import com.walmal.common.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an order is cancelled — by the customer, by staff, or automatically
 * due to a POS sync conflict.
 * Routing key: {@code order.cancelled}
 *
 * <p>The {@code cancellationReason} string allows the Notification module to compose
 * an appropriate customer-facing message without business logic in the listener.</p>
 */
public class OrderCancelledEvent extends DomainEvent {

    private final UUID orderId;
    private final UUID userId;
    private final String cancellationReason;
    private final Instant cancelledAt;

    public OrderCancelledEvent(UUID orderId, UUID userId,
                                String cancellationReason, Instant cancelledAt) {
        super("order.cancelled");
        this.orderId = orderId;
        this.userId = userId;
        this.cancellationReason = cancellationReason;
        this.cancelledAt = cancelledAt;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCancelledAt() { return cancelledAt; }
}
