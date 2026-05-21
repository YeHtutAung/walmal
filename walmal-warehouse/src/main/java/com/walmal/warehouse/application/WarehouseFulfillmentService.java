package com.walmal.warehouse.application;

import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import com.walmal.warehouse.domain.FulfillmentStatus;

import java.util.UUID;

/**
 * Public service interface for warehouse fulfillment operations.
 *
 * <p>ISP: Fulfillment lifecycle is a distinct concern from inventory adjustment.
 * Consumers (operator UI, RabbitMQ listeners) inject only this interface.</p>
 *
 * <p>Architecture rule: callers must never import any Repository or infrastructure
 * class from walmal-warehouse.</p>
 */
public interface WarehouseFulfillmentService {

    /**
     * Returns the full detail of a fulfillment including all lines and shipment info.
     * Enriches the response with current order status from {@code OrderQueryService}.
     *
     * @param orderId the Order module's order UUID
     * @throws com.walmal.common.exception.ResourceNotFoundException if no fulfillment exists
     */
    FulfillmentDetailDto getFulfillment(UUID orderId);

    /**
     * Advances fulfillment status through the picking → packing flow.
     * Valid target values: PICKING (from PENDING) and PACKED (from PICKING).
     * For the PACKED → SHIPPED transition, use {@link #shipFulfillment} instead.
     *
     * @param orderId      the Order module's order UUID
     * @param targetStatus the desired next status
     * @param notes        optional operator notes
     * @throws com.walmal.common.exception.ResourceNotFoundException if no fulfillment exists
     * @throws com.walmal.common.exception.BusinessRuleException    for invalid transitions
     */
    void advanceStatus(UUID orderId, FulfillmentStatus targetStatus, String notes);

    /**
     * Ships the fulfillment (PACKED → SHIPPED).
     *
     * <p>For any line where quantity_picked &lt; quantity_requested:
     * writes {@code audit_log} BEFORE calling {@code InventoryAdjustmentService.adjustStock()}
     * with a negative delta (WRITE_OFF). This is a hard architecture rule — audit must
     * precede mutation.</p>
     *
     * <p>After write-offs, calls {@code OrderFulfillmentService.markFulfilled()} to transition
     * the order to FULFILLED, creates a {@code warehouse_shipments} row, and publishes
     * {@code warehouse.fulfillment.shipped}.</p>
     *
     * @param orderId        the Order module's order UUID
     * @param carrier        carrier name (e.g. "FedEx")
     * @param trackingNumber carrier tracking number
     * @param notes          optional operator notes
     * @throws com.walmal.common.exception.ResourceNotFoundException if no fulfillment exists
     * @throws com.walmal.common.exception.BusinessRuleException    if fulfillment is not PACKED
     */
    void shipFulfillment(UUID orderId, String carrier, String trackingNumber, String notes);

    /**
     * Records the quantity physically picked for a single fulfillment line.
     * Only valid while the fulfillment is in PICKING status.
     *
     * @param lineId          the fulfillment line UUID
     * @param quantityPicked  actual count picked (0 ≤ quantityPicked ≤ quantityRequested)
     * @throws com.walmal.common.exception.ResourceNotFoundException if the line does not exist
     * @throws com.walmal.common.exception.BusinessRuleException    if fulfillment not in PICKING
     */
    void recordPickedQuantity(UUID lineId, int quantityPicked);

    /**
     * Cancels the fulfillment if it is in PENDING or PICKING status.
     * PACKED and SHIPPED fulfillments are non-cancellable.
     *
     * <p>Writes {@code audit_log} before the status mutation. Publishes
     * {@code warehouse.fulfillment.cancelled}. Idempotent if no fulfillment exists
     * for the given orderId (order may not have been confirmed yet).</p>
     *
     * @param orderId the Order module's order UUID
     * @throws com.walmal.common.exception.BusinessRuleException if fulfillment is PACKED or SHIPPED
     */
    void cancelFulfillment(UUID orderId);
}
