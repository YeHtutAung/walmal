package com.walmal.inventory.application;

/**
 * Outcome of a POS offline sync conflict resolution attempt.
 *
 * <p>Returned by {@link InventoryReservationService#resolveConflict} so the POS module
 * can set the correct {@code sync_status} on its {@code pos_sale} record and publish
 * the appropriate RabbitMQ event.</p>
 */
public enum ConflictOutcome {

    /** Stock available at primary or buffer location; deducted without cancelling any web order. */
    NO_CONFLICT,

    /** POS sale timestamp was earlier than the web reservation; web reservation released and stock deducted. */
    POS_PRIORITY,

    /**
     * All stock exhausted (primary and buffer); web reservation released (if one existed).
     * Stock debit is accepted as a reconciliation debt for MVP.
     */
    BUFFER_EXHAUSTED,

    /**
     * No stock available anywhere and no web reservation to release.
     * Stock debit accepted as reconciliation debt; no web order cancelled.
     */
    STOCK_UNAVAILABLE
}
