package com.walmal.inventory.application;

import com.walmal.inventory.domain.ConflictReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cross-module service interface — consumed by the Order module.
 *
 * <p>ISP: Order needs to reserve, confirm, release, and resolve POS conflicts.
 * It does not need raw stock queries or adjustment operations.</p>
 *
 * <p>Architecture rule: Order depends on this interface only. It never imports any
 * Repository or infrastructure class from walmal-inventory.</p>
 */
public interface InventoryReservationService {

    /**
     * A single reservation line: one variant at one location for a given quantity.
     *
     * @param variantId  the product variant UUID
     * @param locationId the inventory location UUID
     * @param quantity   units to reserve (must be positive)
     */
    record ReservationLineItem(UUID variantId, UUID locationId, int quantity) {}

    /**
     * Reserves stock for all line items in a single atomic operation.
     *
     * <p>For each line item:
     * <ol>
     *   <li>Validates variant is active via {@code ProductCatalogService.isVariantActive()}.</li>
     *   <li>Decrements available_quantity, increments reserved_quantity on inventory_stock.</li>
     *   <li>Creates an inventory_reservations row with status=PENDING.</li>
     *   <li>Writes an inventory_movements row of type RESERVATION.</li>
     * </ol>
     * Publishes inventory.stock.low if stock falls to or below threshold.
     * Publishes inventory.stock.exhausted if stock reaches 0.
     * On OptimisticLockException: retries up to 3 times.
     * After 3 failures: throws ConcurrencyConflictException.</p>
     *
     * @param orderId the Order module's order UUID
     * @param items   list of (variantId, locationId, quantity) tuples
     * @throws com.walmal.common.exception.BusinessRuleException        if any variant is inactive or insufficient stock
     * @throws com.walmal.common.exception.ConcurrencyConflictException if optimistic lock fails after 3 retries
     */
    void reserveStock(UUID orderId, List<ReservationLineItem> items);

    /**
     * Confirms all PENDING reservations for the given order (called on payment success).
     * Transitions each reservation to CONFIRMED. Decrements reserved_quantity.
     * Writes SALE movement rows. Publishes inventory.reservation.confirmed.
     *
     * @param orderId the Order module's order UUID
     * @throws com.walmal.common.exception.ResourceNotFoundException if no PENDING reservations exist
     */
    void confirmReservation(UUID orderId);

    /**
     * Releases all PENDING reservations for the given order.
     * Increments available_quantity, decrements reserved_quantity.
     * Writes audit_log before each stock mutation.
     * Writes RELEASE movement rows.
     * Publishes inventory.reservation.released (includes conflictReason in payload).
     *
     * @param orderId        the Order module's order UUID
     * @param conflictReason reason for release (CANCELLED, POS_PRIORITY, BUFFER_EXHAUSTED, EXPIRED)
     * @throws com.walmal.common.exception.ResourceNotFoundException if no PENDING reservations exist
     */
    void releaseReservation(UUID orderId, ConflictReason conflictReason);

    /**
     * Resolves a POS offline sync conflict for a single variant-location pair.
     * Called by POS module during online sync. See ADR-4 for the full algorithm.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>If webOrderId is not null and posSaleTimestamp is before the web reservation
     *       createdAt → POS wins: release web reservation with POS_PRIORITY, deduct from stock.</li>
     *   <li>Otherwise attempt direct stock deduction at locationId.</li>
     *   <li>If primary location insufficient → try buffer locations.</li>
     *   <li>If buffer exhausted → release web reservation with BUFFER_EXHAUSTED.</li>
     * </ol>
     * Writes audit_log before any destructive stock mutation.
     * Publishes inventory.reservation.released if a web reservation is cancelled.</p>
     *
     * @param posSaleId        UUID of the POS sale record (reference_id in movements)
     * @param variantId        the variant being sold
     * @param locationId       the POS store's inventory location
     * @param quantity         units sold offline
     * @param posSaleTimestamp when the POS sale was recorded (offline device clock)
     * @param webOrderId       the conflicting web order UUID (null if no conflict)
     */
    void resolveConflict(UUID posSaleId, UUID variantId, UUID locationId,
                         int quantity, Instant posSaleTimestamp, UUID webOrderId);
}
