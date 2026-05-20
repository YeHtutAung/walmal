package com.walmal.inventory.domain;

import com.walmal.common.model.BaseEntity;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Represents a physical or logical stock location (warehouse floor, buffer pool, POS back-room).
 *
 * <p>{@code externalReferenceId} is a plain UUID with no @ManyToOne — it stores a cross-module
 * reference (e.g. a Warehouse UUID) without declaring an FK constraint. Module boundary rule.</p>
 *
 * <p>The SQL schema does NOT define a location_type column. The enum is kept here for
 * future use. The SQL uses name/external_reference_id/is_buffer_location to classify locations.</p>
 *
 * <p>{@code BaseEntity} provides: id, createdAt, updatedAt, @PrePersist, @PreUpdate.</p>
 */
@Entity
@Table(name = "inventory_locations")
public class InventoryLocation extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "external_reference_id")
    private UUID externalReferenceId;   // no @ManyToOne — plain UUID, cross-module ref

    @Column(name = "is_buffer_location", nullable = false)
    private boolean bufferLocation = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected InventoryLocation() {}

    public InventoryLocation(String name, UUID externalReferenceId,
                             boolean bufferLocation, boolean active) {
        this.name = name;
        this.externalReferenceId = externalReferenceId;
        this.bufferLocation = bufferLocation;
        this.active = active;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getExternalReferenceId() { return externalReferenceId; }
    public void setExternalReferenceId(UUID externalReferenceId) {
        this.externalReferenceId = externalReferenceId;
    }

    public boolean isBufferLocation() { return bufferLocation; }
    public void setBufferLocation(boolean bufferLocation) { this.bufferLocation = bufferLocation; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
