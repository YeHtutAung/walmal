package com.walmal.warehouse.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Shipment record created when a fulfillment transitions to SHIPPED status.
 * One-to-one with {@link FulfillmentOrder}. Insert-only — never updated.
 */
@Entity
@Table(name = "warehouse_shipments")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fulfillment_id", nullable = false, unique = true)
    private FulfillmentOrder fulfillmentOrder;

    @Column(name = "carrier", nullable = false, length = 100)
    private String carrier;

    @Column(name = "tracking_number", nullable = false, length = 255)
    private String trackingNumber;

    @Column(name = "shipped_at", nullable = false)
    private Instant shippedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Shipment() {}

    public Shipment(FulfillmentOrder fulfillmentOrder, String carrier,
                    String trackingNumber, Instant shippedAt) {
        this.fulfillmentOrder = fulfillmentOrder;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.shippedAt = shippedAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public FulfillmentOrder getFulfillmentOrder() { return fulfillmentOrder; }
    public String getCarrier() { return carrier; }
    public String getTrackingNumber() { return trackingNumber; }
    public Instant getShippedAt() { return shippedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
