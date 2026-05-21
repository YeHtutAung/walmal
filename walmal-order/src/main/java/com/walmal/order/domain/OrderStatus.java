package com.walmal.order.domain;

/**
 * Lifecycle states for an {@link Order}.
 *
 * <p>State machine:
 * <pre>
 *   PENDING → CONFIRMED (payment succeeded)
 *   PENDING → CANCELLED (customer cancel, payment failed, or POS sync conflict)
 *   CONFIRMED → FULFILLED (goods dispatched)
 *   CONFIRMED → CANCELLED (admin cancel — requires audit justification)
 * </pre>
 * </p>
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FULFILLED,
    CANCELLED
}
