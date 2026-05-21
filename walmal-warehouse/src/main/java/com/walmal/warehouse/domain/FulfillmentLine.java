package com.walmal.warehouse.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One picking line within a {@link FulfillmentOrder}.
 *
 * <p>Insert-only after fulfillment creation, except {@code quantityPicked} which is
 * updated by warehouse operators during the PICKING phase.</p>
 *
 * <p>{@code variantId} and {@code locationId} are cross-module UUID references — no FK.</p>
 */
@Entity
@Table(name = "warehouse_fulfillment_lines")
public class FulfillmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fulfillment_id", nullable = false)
    private FulfillmentOrder fulfillmentOrder;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "sku_snapshot", nullable = false, length = 100)
    private String skuSnapshot;

    @Column(name = "quantity_requested", nullable = false)
    private int quantityRequested;

    @Column(name = "quantity_picked", nullable = false)
    private int quantityPicked = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FulfillmentLine() {}

    public FulfillmentLine(FulfillmentOrder fulfillmentOrder,
                           UUID variantId, UUID locationId,
                           String skuSnapshot, int quantityRequested) {
        this.fulfillmentOrder = fulfillmentOrder;
        this.variantId = variantId;
        this.locationId = locationId;
        this.skuSnapshot = skuSnapshot;
        this.quantityRequested = quantityRequested;
        this.quantityPicked = 0;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public int getDiscrepancy() {
        return quantityRequested - quantityPicked;
    }

    public boolean hasDiscrepancy() {
        return quantityPicked < quantityRequested;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public FulfillmentOrder getFulfillmentOrder() { return fulfillmentOrder; }
    public UUID getVariantId() { return variantId; }
    public UUID getLocationId() { return locationId; }
    public String getSkuSnapshot() { return skuSnapshot; }
    public int getQuantityRequested() { return quantityRequested; }
    public int getQuantityPicked() { return quantityPicked; }
    public Instant getCreatedAt() { return createdAt; }

    public void setQuantityPicked(int quantityPicked) {
        this.quantityPicked = quantityPicked;
    }
}
