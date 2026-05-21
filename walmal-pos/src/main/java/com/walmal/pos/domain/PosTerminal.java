package com.walmal.pos.domain;

import com.walmal.common.exception.BusinessRuleException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate entity for a physical POS register at a store location.
 *
 * <p>Table: {@code pos_terminals}</p>
 *
 * <p>{@code locationId} is a cross-module UUID reference to {@code inventory_locations.id}.
 * No FK is declared — module boundary rule. The Inventory module owns that table.</p>
 *
 * <p>No {@code @Version} column. Register/deactivate are low-frequency sequential operations.</p>
 */
@Entity
@Table(name = "pos_terminals")
public class PosTerminal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;   // cross-module ref to inventory_locations — no FK

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TerminalStatus status = TerminalStatus.ACTIVE;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PosTerminal() {}

    public PosTerminal(String name, UUID locationId) {
        this.name = name;
        this.locationId = locationId;
        this.status = TerminalStatus.ACTIVE;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── State mutations ───────────────────────────────────────────────────────

    /**
     * Transitions terminal to INACTIVE.
     *
     * @throws BusinessRuleException if the terminal is already INACTIVE
     */
    public void deactivate() {
        if (this.status == TerminalStatus.INACTIVE) {
            throw new BusinessRuleException(
                    "Terminal " + id + " is already INACTIVE");
        }
        this.status = TerminalStatus.INACTIVE;
    }

    /**
     * Updates {@code lastSeenAt} to now, recording that the terminal made an authenticated call.
     */
    public void recordActivity() {
        this.lastSeenAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getLocationId() { return locationId; }
    public TerminalStatus getStatus() { return status; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
