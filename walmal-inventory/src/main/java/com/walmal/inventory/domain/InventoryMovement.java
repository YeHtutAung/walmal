package com.walmal.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only ledger of every stock quantity change.
 *
 * <p>This entity does NOT extend {@link com.walmal.common.model.BaseEntity} because the
 * {@code inventory_movements} table has no {@code updated_at} column. Extending BaseEntity
 * would cause Hibernate schema validation to fail. All fields are declared directly here.</p>
 *
 * <p>This entity is INSERT-ONLY. No UPDATE or DELETE is ever performed on rows in this table.
 * All columns are {@code updatable = false} to enforce this at the ORM layer.</p>
 */
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false, updatable = false)
    private InventoryLocation location;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20, updatable = false)
    private MovementType movementType;

    @Column(name = "quantity_delta", nullable = false, updatable = false)
    private int quantityDelta;          // signed: positive=inflow, negative=outflow

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;           // nullable: reservation_id, order_id, etc.

    @Column(name = "performed_by", nullable = false, length = 255, updatable = false)
    private String performedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // NO updated_at column in this table — insert-only by design

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    protected InventoryMovement() {}

    public InventoryMovement(UUID variantId, InventoryLocation location,
                              MovementType movementType, int quantityDelta,
                              UUID referenceId, String performedBy) {
        this.variantId = variantId;
        this.location = location;
        this.movementType = movementType;
        this.quantityDelta = quantityDelta;
        this.referenceId = referenceId;
        this.performedBy = performedBy;
    }

    public UUID getId() { return id; }
    public UUID getVariantId() { return variantId; }
    public InventoryLocation getLocation() { return location; }
    public MovementType getMovementType() { return movementType; }
    public int getQuantityDelta() { return quantityDelta; }
    public UUID getReferenceId() { return referenceId; }
    public String getPerformedBy() { return performedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
