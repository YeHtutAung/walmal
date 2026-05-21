package com.walmal.order.domain.event;

import com.walmal.common.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when a new order is first persisted (PENDING state).
 * Routing key: {@code order.created}
 *
 * <p>The {@code items} list uses an immutable inner record so that consumers receive
 * a complete snapshot without coupling to the Order domain entity.</p>
 */
public class OrderCreatedEvent extends DomainEvent {

    /**
     * A single line item snapshot included in the event payload.
     */
    public record OrderItemSnapshot(
            UUID variantId,
            UUID locationId,
            int quantity,
            String skuSnapshot
    ) {}

    private final UUID orderId;
    private final UUID userId;
    private final List<OrderItemSnapshot> items;
    private final BigDecimal totalAmount;
    private final String currency;
    private final Instant createdAt;

    public OrderCreatedEvent(UUID orderId, UUID userId, List<OrderItemSnapshot> items,
                              BigDecimal totalAmount, String currency, Instant createdAt) {
        super("order.created");
        this.orderId = orderId;
        this.userId = userId;
        this.items = List.copyOf(items);
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public List<OrderItemSnapshot> getItems() { return items; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
