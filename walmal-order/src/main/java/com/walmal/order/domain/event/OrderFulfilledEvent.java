package com.walmal.order.domain.event;

import com.walmal.common.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an order transitions CONFIRMED → FULFILLED (goods dispatched).
 * Routing key: {@code order.fulfilled}
 */
public class OrderFulfilledEvent extends DomainEvent {

    private final UUID orderId;
    private final UUID userId;
    private final Instant fulfilledAt;

    public OrderFulfilledEvent(UUID orderId, UUID userId, Instant fulfilledAt) {
        super("order.fulfilled");
        this.orderId = orderId;
        this.userId = userId;
        this.fulfilledAt = fulfilledAt;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public Instant getFulfilledAt() { return fulfilledAt; }
}
