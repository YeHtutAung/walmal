package com.walmal.warehouse.domain.event;

import com.walmal.common.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a fulfillment transitions PACKED → SHIPPED.
 * Routing key: {@code warehouse.fulfillment.shipped}
 *
 * <p>Notification module uses this to send "Your order has shipped" email with tracking info.</p>
 */
public class FulfillmentShippedEvent extends DomainEvent {

    private final UUID orderId;
    private final UUID userId;
    private final String carrier;
    private final String trackingNumber;
    private final Instant shippedAt;

    public FulfillmentShippedEvent(UUID orderId, UUID userId,
                                    String carrier, String trackingNumber, Instant shippedAt) {
        super("warehouse.fulfillment.shipped");
        this.orderId = orderId;
        this.userId = userId;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.shippedAt = shippedAt;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public String getCarrier() { return carrier; }
    public String getTrackingNumber() { return trackingNumber; }
    public Instant getShippedAt() { return shippedAt; }
}
