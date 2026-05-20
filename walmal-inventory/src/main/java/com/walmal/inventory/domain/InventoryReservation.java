package com.walmal.inventory.domain;

import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.model.BaseEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a stock hold placed by the Order module when an order is created.
 *
 * <p>State machine: PENDING → CONFIRMED (payment success) | RELEASED (cancel/expiry/conflict).</p>
 *
 * <p>{@code orderId} and {@code variantId} are cross-module plain UUID references — no FK
 * constraints. Module boundary rule.</p>
 *
 * <p>{@code conflictReason} is null until status transitions to RELEASED.</p>
 *
 * <p>{@code BaseEntity} provides: id, createdAt, updatedAt, @PrePersist, @PreUpdate.</p>
 */
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;                // plain UUID — no FK to order module

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;             // plain UUID — no FK to product module

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private InventoryLocation location;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_reason", length = 30)
    private ConflictReason conflictReason;   // null until RELEASED

    protected InventoryReservation() {}

    public InventoryReservation(UUID orderId, UUID variantId,
                                 InventoryLocation location, int quantity, Instant expiresAt) {
        this.orderId = orderId;
        this.variantId = variantId;
        this.location = location;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
        this.status = ReservationStatus.PENDING;
    }

    // ── State machine ─────────────────────────────────────────────────────────

    /** Transitions PENDING → CONFIRMED. */
    public void confirm() {
        if (this.status != ReservationStatus.PENDING) {
            throw new BusinessRuleException(
                    "Only PENDING reservations can be confirmed. Current status: " + this.status);
        }
        this.status = ReservationStatus.CONFIRMED;
    }

    /** Transitions PENDING/CONFIRMED → RELEASED, recording the reason. */
    public void release(ConflictReason reason) {
        if (this.status == ReservationStatus.RELEASED) {
            throw new BusinessRuleException("Reservation is already RELEASED");
        }
        this.status = ReservationStatus.RELEASED;
        this.conflictReason = reason;
    }

    public boolean isExpired() {
        return this.status == ReservationStatus.PENDING
                && Instant.now().isAfter(this.expiresAt);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getOrderId() { return orderId; }
    public UUID getVariantId() { return variantId; }
    public InventoryLocation getLocation() { return location; }
    public int getQuantity() { return quantity; }
    public ReservationStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public ConflictReason getConflictReason() { return conflictReason; }
}
