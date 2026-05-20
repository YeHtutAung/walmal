package com.walmal.inventory.application;

import java.util.UUID;

/**
 * Cross-module service interface — consumed by the Warehouse module.
 *
 * <p>ISP: Warehouse needs to post stock receipts, make corrections, and transfer stock.
 * It does not need reservation lifecycle management or POS conflict resolution.</p>
 */
public interface InventoryAdjustmentService {

    /**
     * Applies a signed quantity delta to a variant-location stock row.
     * Positive delta: stock inflow (receipt, correction upward).
     * Negative delta: stock outflow (write-off, correction downward).
     *
     * <p>Writes audit_log before applying a negative delta (destructive).
     * Writes an inventory_movements row (RECEIPT for +delta, ADJUSTMENT for either sign).
     * Evicts cache keys for this variant and location after mutation.
     * Publishes inventory.stock.low if applicable. Publishes inventory.stock.exhausted
     * if available_quantity reaches 0.</p>
     *
     * @param variantId   the variant being adjusted
     * @param locationId  the location being adjusted
     * @param delta       signed integer — negative values decrement available_quantity
     * @param reason      human-readable reason (stored in audit_log)
     * @param performedBy username of the operator performing the adjustment
     * @throws com.walmal.common.exception.BusinessRuleException if delta would make quantity negative
     */
    void adjustStock(UUID variantId, UUID locationId, int delta,
                     String reason, String performedBy);

    /**
     * Transfers stock from one location to another in a single transaction.
     *
     * <p>Writes audit_log for the source location decrement.
     * Writes two inventory_movements rows: TRANSFER_OUT (source), TRANSFER_IN (destination).
     * Evicts cache for both source and destination variant-location pairs.</p>
     *
     * @param variantId      the variant being transferred
     * @param fromLocationId source location
     * @param toLocationId   destination location
     * @param quantity       units to transfer (must be positive)
     * @param performedBy    username of the warehouse operator
     * @throws com.walmal.common.exception.BusinessRuleException     if source has insufficient stock
     * @throws com.walmal.common.exception.ResourceNotFoundException if either location does not exist
     */
    void transferStock(UUID variantId, UUID fromLocationId, UUID toLocationId,
                       int quantity, String performedBy);

    /**
     * Updates the low stock threshold for a variant at a specific location.
     *
     * @param variantId   the variant
     * @param locationId  the location
     * @param threshold   new threshold value (must be >= 0)
     * @param performedBy username authorising the change
     */
    void updateLowStockThreshold(UUID variantId, UUID locationId,
                                  int threshold, String performedBy);
}
