package com.walmal.order.domain.event;

import com.walmal.common.event.DomainEvent;
import com.walmal.order.domain.ShippingAddress;
import com.walmal.order.domain.event.OrderCreatedEvent.OrderItemSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when payment succeeds and an order transitions PENDING → CONFIRMED.
 * Routing key: {@code order.confirmed}
 *
 * <p>Includes the shipping address so that downstream modules (Notification, Warehouse)
 * can compose dispatch instructions without a cross-module lookup.</p>
 */
public class OrderConfirmedEvent extends DomainEvent {

    private final UUID orderId;
    private final UUID userId;
    private final List<OrderItemSnapshot> items;
    private final ShippingAddress shippingAddress;
    private final Instant confirmedAt;

    public OrderConfirmedEvent(UUID orderId, UUID userId, List<OrderItemSnapshot> items,
                                ShippingAddress shippingAddress, Instant confirmedAt) {
        super("order.confirmed");
        this.orderId = orderId;
        this.userId = userId;
        this.items = List.copyOf(items);
        this.shippingAddress = shippingAddress;
        this.confirmedAt = confirmedAt;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public List<OrderItemSnapshot> getItems() { return items; }
    public ShippingAddress getShippingAddress() { return shippingAddress; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
