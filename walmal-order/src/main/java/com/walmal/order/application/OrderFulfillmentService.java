package com.walmal.order.application;

import java.util.UUID;

/**
 * Public service interface for order fulfilment.
 *
 * <p>ISP: fulfilment is a distinct concern consumed only by warehouse/admin actors.
 * Segregated from creation and query interfaces to avoid forcing those callers to
 * depend on this method.</p>
 */
public interface OrderFulfillmentService {

    /**
     * Transitions a CONFIRMED order to FULFILLED.
     *
     * <p>Writes to {@code audit_log} BEFORE mutating the order status.
     * Publishes {@code order.fulfilled}.</p>
     *
     * @param orderId the order to mark as fulfilled
     * @throws com.walmal.common.exception.ResourceNotFoundException if the order does not exist
     * @throws com.walmal.common.exception.BusinessRuleException    if the order is not CONFIRMED
     */
    void markFulfilled(UUID orderId);
}
