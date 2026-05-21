package com.walmal.pos.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Line item within a POS sale. Insert-only — rows are never modified after the sale is recorded.
 *
 * <p>Table: {@code pos_sale_items}</p>
 *
 * <p>{@code variantId} and {@code locationId} are cross-module UUID references.
 * No FK constraints declared — module boundary rule.</p>
 *
 * <p>Snapshot fields ({@code productNameSnapshot}, {@code skuSnapshot}, {@code priceAtSale})
 * are copied from the Product module at sale time and are immutable thereafter.</p>
 *
 * <p>No {@code updatedAt} — insert-only table by design.</p>
 */
@Entity
@Table(name = "pos_sale_items")
public class PosSaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private PosSale sale;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;   // cross-module ref to product_variants — no FK

    @Column(name = "product_name_snapshot", nullable = false, length = 500)
    private String productNameSnapshot;

    @Column(name = "sku_snapshot", nullable = false, length = 100)
    private String skuSnapshot;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "price_at_sale", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAtSale;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;   // cross-module ref to inventory_locations — no FK

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PosSaleItem() {}

    public PosSaleItem(PosSale sale, UUID variantId, String productNameSnapshot,
                       String skuSnapshot, int quantity, BigDecimal priceAtSale,
                       String currency, BigDecimal subtotal, UUID locationId) {
        this.sale = sale;
        this.variantId = variantId;
        this.productNameSnapshot = productNameSnapshot;
        this.skuSnapshot = skuSnapshot;
        this.quantity = quantity;
        this.priceAtSale = priceAtSale;
        this.currency = currency;
        this.subtotal = subtotal;
        this.locationId = locationId;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // ── Getters — no setters: insert-only entity ──────────────────────────────

    public UUID getId() { return id; }
    public PosSale getSale() { return sale; }
    public UUID getVariantId() { return variantId; }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public String getSkuSnapshot() { return skuSnapshot; }
    public int getQuantity() { return quantity; }
    public BigDecimal getPriceAtSale() { return priceAtSale; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public UUID getLocationId() { return locationId; }
    public Instant getCreatedAt() { return createdAt; }
}
