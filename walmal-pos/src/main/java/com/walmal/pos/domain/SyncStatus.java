package com.walmal.pos.domain;

/**
 * Sync lifecycle status of a POS sale.
 *
 * <p>N_A              — online sale; no sync needed (set at creation).
 * PENDING           — offline sale queued for sync; not yet processed.
 * SYNCED            — offline sale successfully validated and stock applied.
 * CONFLICT_RESOLVED — offline sale had a stock conflict; conflict resolver determined the outcome.
 * FAILED            — offline sale sync permanently failed; requires audit_log write.</p>
 */
public enum SyncStatus {
    N_A,
    PENDING,
    SYNCED,
    CONFLICT_RESOLVED,
    FAILED
}
