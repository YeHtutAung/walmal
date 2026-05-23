package com.walmal.warehouse.domain;

import com.walmal.common.exception.BusinessRuleException;
import jakarta.persistence.*;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for the Warehouse bounded context.
 *
 * <p>Tracks the fulfillment lifecycle from PENDING through SHIPPED or CANCELLED.
 * {@code orderId} is a cross-module UUID reference to {@code order_orders.id} — no FK.</p>
 *
 * <p>{@code shippingAddress} is stored as a JSON string snapshot taken at fulfillment
 * creation time. Never updated after creation.</p>
 *
 * <p>{@code version} supports JPA optimistic locking for concurrent operator actions.</p>
 */
@Entity
@Table(name = "warehouse_fulfillments")
public class FulfillmentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FulfillmentStatus status = FulfillmentStatus.PENDING;

    @Column(name = "shipping_address", nullable = false, columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Formula("(SELECT COUNT(*) FROM warehouse_fulfillment_lines wfl WHERE wfl.fulfillment_id = id)")
    private int lineCount;

    @OneToMany(mappedBy = "fulfillmentOrder", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<FulfillmentLine> lines = new ArrayList<>();

    protected FulfillmentOrder() {}

    public FulfillmentOrder(UUID orderId, UUID userId, String shippingAddress) {
        this.orderId = orderId;
        this.userId = userId;
        this.shippingAddress = shippingAddress;
        this.status = FulfillmentStatus.PENDING;
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

    // ── State machine ─────────────────────────────────────────────────────────

    /**
     * Advances status through the standard picking → packing flow.
     * Valid transitions: PENDING→PICKING, PICKING→PACKED.
     *
     * @throws BusinessRuleException for invalid transitions
     */
    public void advanceTo(FulfillmentStatus target, String notes) {
        boolean valid = switch (target) {
            case PICKING   -> this.status == FulfillmentStatus.PENDING;
            case PACKED    -> this.status == FulfillmentStatus.PICKING;
            case SHIPPED   -> this.status == FulfillmentStatus.PACKED;
            case CANCELLED -> this.status == FulfillmentStatus.PENDING
                              || this.status == FulfillmentStatus.PICKING;
            default        -> false;
        };
        if (!valid) {
            throw new BusinessRuleException(
                    "Cannot transition fulfillment from " + this.status + " to " + target);
        }
        this.status = target;
        if (notes != null && !notes.isBlank()) {
            this.notes = notes;
        }
    }

    /**
     * Cancels the fulfillment. Only valid from PENDING or PICKING.
     */
    public void cancel() {
        advanceTo(FulfillmentStatus.CANCELLED, null);
    }

    public boolean isCancellable() {
        return this.status == FulfillmentStatus.PENDING
                || this.status == FulfillmentStatus.PICKING;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public int getLineCount() { return lineCount; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public FulfillmentStatus getStatus() { return status; }
    public String getShippingAddress() { return shippingAddress; }
    public String getNotes() { return notes; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<FulfillmentLine> getLines() { return Collections.unmodifiableList(lines); }
}
