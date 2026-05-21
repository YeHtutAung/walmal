package com.walmal.pos.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Holds a raw offline sale payload submitted by a POS terminal after reconnection.
 *
 * <p>Table: {@code pos_sync_queue}</p>
 *
 * <p>SOFT-COMPLETE ONLY. Rows in this table are NEVER hard-deleted. Once created, a row
 * may only transition to PROCESSED or FAILED. Hard deletes are architecture violations
 * in the POS module — this table is an append-only operational log.</p>
 *
 * <p>{@code saleData} stores the raw JSON payload from the device as JSONB.
 * The sync processor deserializes it to {@code OfflineSalePayload} via ObjectMapper.</p>
 */
@Entity
@Table(name = "pos_sync_queue")
public class PosSyncQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terminal_id", nullable = false)
    private PosTerminal terminal;

    @Column(name = "sale_data", nullable = false, columnDefinition = "jsonb")
    private String saleData;   // raw JSON payload — deserialized to OfflineSalePayload by processor

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "processed_at")
    private Instant processedAt;   // null until PROCESSED

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueStatus status = QueueStatus.PENDING;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;   // null unless FAILED

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PosSyncQueue() {}

    public PosSyncQueue(PosTerminal terminal, String saleData) {
        this.terminal = terminal;
        this.saleData = saleData;
        this.status = QueueStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.submittedAt == null) {
            this.submittedAt = Instant.now();
        }
    }

    // ── State mutations ───────────────────────────────────────────────────────

    /**
     * Transitions status to PROCESSED and records processing time.
     * No audit log required — PROCESSED is a positive outcome transition.
     */
    public void markProcessed() {
        this.status = QueueStatus.PROCESSED;
        this.processedAt = Instant.now();
    }

    /**
     * Transitions status to FAILED with a failure reason.
     * Caller MUST write to audit_log BEFORE calling this method (architecture rule).
     *
     * @param reason human-readable failure reason for operator investigation
     */
    public void markFailed(String reason) {
        this.status = QueueStatus.FAILED;
        this.failureReason = reason;
    }

    // ── Getters — no delete method (rows never hard-deleted) ──────────────────

    public UUID getId() { return id; }
    public PosTerminal getTerminal() { return terminal; }
    public String getSaleData() { return saleData; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public QueueStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
