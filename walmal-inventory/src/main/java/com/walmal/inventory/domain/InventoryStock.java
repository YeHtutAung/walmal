package com.walmal.inventory.domain;

import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.model.BaseEntity;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Authoritative real-time stock record for one (variant_id, location_id) pair.
 *
 * <p>{@code variantId} is a plain UUID — no @ManyToOne to product module. Module boundary rule.</p>
 *
 * <p>{@code version} is the JPA {@code @Version} field for optimistic locking. Hibernate
 * increments it on every UPDATE and verifies it on write. Never modify this field manually.</p>
 *
 * <p>{@code BaseEntity} provides: id, createdAt, updatedAt, @PrePersist, @PreUpdate.</p>
 */
@Entity
@Table(name = "inventory_stock",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_inv_stock_variant_location",
               columnNames = {"variant_id", "location_id"}))
public class InventoryStock extends BaseEntity {

    /** Upper multiple of {@code lowStockThreshold} still classified as LOW by {@link #classifyHealth()}. */
    private static final int LOW_STOCK_BAND_MULTIPLIER = 2;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;             // plain UUID — no FK to product module

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private InventoryLocation location;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity = 0;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold = 10;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0;           // optimistic locking — Hibernate manages this

    protected InventoryStock() {}

    public InventoryStock(UUID variantId, InventoryLocation location,
                          int availableQuantity, int lowStockThreshold) {
        this.variantId = variantId;
        this.location = location;
        this.availableQuantity = availableQuantity;
        this.lowStockThreshold = lowStockThreshold;
    }

    // ── Domain guard methods ──────────────────────────────────────────────────

    /**
     * Moves {@code quantity} units from available pool to reserved pool.
     * Throws {@link BusinessRuleException} if insufficient stock.
     */
    public void reserve(int quantity) {
        if (this.availableQuantity < quantity) {
            throw new BusinessRuleException(
                    "Insufficient stock: available=" + availableQuantity + ", requested=" + quantity);
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
    }

    /**
     * Confirms a sale — stock has left the system.
     * Decrements reserved_quantity only; available_quantity does not change.
     */
    public void confirm(int quantity) {
        if (this.reservedQuantity < quantity) {
            throw new BusinessRuleException(
                    "Cannot confirm: reserved=" + reservedQuantity + ", confirming=" + quantity);
        }
        this.reservedQuantity -= quantity;
    }

    /**
     * Returns reserved stock back to the available pool (order cancel / expiry / conflict).
     */
    public void release(int quantity) {
        if (this.reservedQuantity < quantity) {
            throw new BusinessRuleException(
                    "Cannot release: reserved=" + reservedQuantity + ", releasing=" + quantity);
        }
        this.reservedQuantity -= quantity;
        this.availableQuantity += quantity;
    }

    /** Applies a signed delta to available_quantity (used for adjustments). */
    public void applyDelta(int delta) {
        if (this.availableQuantity + delta < 0) {
            throw new BusinessRuleException(
                    "Adjustment would result in negative stock: current=" + availableQuantity
                    + ", delta=" + delta);
        }
        this.availableQuantity += delta;
    }

    public boolean canReserve(int quantity) {
        return this.availableQuantity >= quantity;
    }

    public boolean isBelowLowStockThreshold() {
        return this.availableQuantity <= this.lowStockThreshold && this.availableQuantity > 0;
    }

    public boolean isExhausted() {
        return this.availableQuantity == 0;
    }

    // CRITICAL at/below threshold, LOW up to LOW_STOCK_BAND_MULTIPLIER x threshold, OK above that.
    public StockHealthStatus classifyHealth() {
        if (this.availableQuantity <= this.lowStockThreshold) {
            return StockHealthStatus.CRITICAL;
        }
        if (this.availableQuantity <= this.lowStockThreshold * LOW_STOCK_BAND_MULTIPLIER) {
            return StockHealthStatus.LOW;
        }
        return StockHealthStatus.OK;
    }

    // ── Getters / setters ─────────────────────────────────────────────────────

    public UUID getVariantId() { return variantId; }
    public InventoryLocation getLocation() { return location; }

    public int getAvailableQuantity() { return availableQuantity; }
    public void setAvailableQuantity(int availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public int getReservedQuantity() { return reservedQuantity; }
    public void setReservedQuantity(int reservedQuantity) {
        this.reservedQuantity = reservedQuantity;
    }

    public int getLowStockThreshold() { return lowStockThreshold; }
    public void setLowStockThreshold(int lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }

    public long getVersion() { return version; }
}
