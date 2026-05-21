package com.walmal.pos.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for a completed POS sale.
 *
 * <p>Table: {@code pos_sales}</p>
 *
 * <p>{@code onlineOrderId} is a nullable cross-module UUID ref to {@code order_orders.id}.
 * Null for offline sales; populated for online sales with the UUID from OrderCreationService.
 * No FK declared — module boundary rule.</p>
 *
 * <p>{@code cashierId} is a nullable cross-module UUID ref to {@code auth_users.id}. No FK.</p>
 *
 * <p>No {@code @Version} column. Sales are append-only at creation; sync_status is updated
 * once (PENDING → SYNCED or CONFLICT_RESOLVED) and never reverted.</p>
 */
@Entity
@Table(name = "pos_sales")
public class PosSale {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terminal_id", nullable = false)
    private PosTerminal terminal;

    @Column(name = "online_order_id")
    private UUID onlineOrderId;   // cross-module ref — no FK; null for offline sales

    @Column(name = "sold_at", nullable = false)
    private Instant soldAt;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_mode", nullable = false, length = 10)
    private SaleMode saleMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 25)
    private SyncStatus syncStatus = SyncStatus.N_A;

    @Column(name = "cashier_id")
    private UUID cashierId;   // cross-module ref to auth_users — no FK; nullable

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PosSaleItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PosSale() {}

    public PosSale(PosTerminal terminal, UUID onlineOrderId, Instant soldAt,
                   BigDecimal totalAmount, String currency, SaleMode saleMode,
                   SyncStatus syncStatus, UUID cashierId) {
        this.terminal = terminal;
        this.onlineOrderId = onlineOrderId;
        this.soldAt = soldAt;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.saleMode = saleMode;
        this.syncStatus = syncStatus;
        this.cashierId = cashierId;
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

    /** Transitions sync_status to SYNCED (offline sale successfully applied). */
    public void markSynced() {
        this.syncStatus = SyncStatus.SYNCED;
    }

    /** Transitions sync_status to CONFLICT_RESOLVED (offline sale had a stock conflict). */
    public void markConflictResolved() {
        this.syncStatus = SyncStatus.CONFLICT_RESOLVED;
    }

    /** Transitions sync_status to FAILED (sync permanently failed). */
    public void markFailed() {
        this.syncStatus = SyncStatus.FAILED;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public PosTerminal getTerminal() { return terminal; }
    public UUID getOnlineOrderId() { return onlineOrderId; }
    public Instant getSoldAt() { return soldAt; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public SaleMode getSaleMode() { return saleMode; }
    public SyncStatus getSyncStatus() { return syncStatus; }
    public UUID getCashierId() { return cashierId; }
    public List<PosSaleItem> getItems() { return Collections.unmodifiableList(items); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
