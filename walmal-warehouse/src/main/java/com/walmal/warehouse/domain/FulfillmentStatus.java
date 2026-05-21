package com.walmal.warehouse.domain;

/**
 * State machine for a warehouse fulfillment order.
 *
 * <pre>
 * PENDING → PICKING → PACKED → SHIPPED
 * PENDING → CANCELLED
 * PICKING → CANCELLED
 * (PACKED and SHIPPED are non-cancellable terminal states)
 * </pre>
 */
public enum FulfillmentStatus {
    PENDING,
    PICKING,
    PACKED,
    SHIPPED,
    CANCELLED
}
