package com.walmal.order.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single line item within an {@link Order}.
 *
 * <p>Insert-only after order creation — rows are never modified. Price, quantity, and
 * product snapshots are fixed at the time the order is placed. No {@code updatedAt}
 * column exists on the backing table by design.</p>
 *
 * <p>{@code variantId} is a cross-module UUID reference to the Product module.
 * No FK constraint — module boundary rule.</p>
 *
 * <p>{@code locationId} is a cross-module UUID reference to the Inventory module's location.
 * Stored here so that cancellation can pass the correct locationId back to
 * {@link com.walmal.inventory.application.InventoryReservationService} without a cross-module JOIN.</p>
 *
 * <p>Getters only — no setters (immutable after construction).</p>
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;                  // cross-module UUID ref — no FK

    @Column(name = "product_name_snapshot", nullable = false, length = 500)
    private String productNameSnapshot;

    @Column(name = "sku_snapshot", nullable = false, length = 100)
    private String skuSnapshot;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "price_at_purchase", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAtPurchase;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;                 // cross-module UUID ref — no FK

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderItem() {}

    public OrderItem(Order order, UUID variantId, String productNameSnapshot,
                     String skuSnapshot, int quantity, BigDecimal priceAtPurchase,
                     String currency, BigDecimal subtotal, UUID locationId) {
        this.order = order;
        this.variantId = variantId;
        this.productNameSnapshot = productNameSnapshot;
        this.skuSnapshot = skuSnapshot;
        this.quantity = quantity;
        this.priceAtPurchase = priceAtPurchase;
        this.currency = currency;
        this.subtotal = subtotal;
        this.locationId = locationId;
        this.createdAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public Order getOrder() { return order; }
    public UUID getVariantId() { return variantId; }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public String getSkuSnapshot() { return skuSnapshot; }
    public int getQuantity() { return quantity; }
    public BigDecimal getPriceAtPurchase() { return priceAtPurchase; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public UUID getLocationId() { return locationId; }
    public Instant getCreatedAt() { return createdAt; }
}
