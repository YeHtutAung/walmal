package com.walmal.pos.domain;

/**
 * Processing status of a pos_sync_queue row.
 *
 * <p>PENDING   — submitted by the terminal; not yet processed.
 * PROCESSED — sync completed successfully (sale is SYNCED or CONFLICT_RESOLVED).
 * FAILED    — processing permanently failed; failure_reason is populated.
 *             Rows in FAILED state are never hard-deleted — they remain for operator investigation.</p>
 */
public enum QueueStatus {
    PENDING,
    PROCESSED,
    FAILED
}
