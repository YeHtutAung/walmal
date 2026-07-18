package com.walmal.order.domain;

/**
 * Reconciliation flag for a persisted {@code payment_webhook_events} row.
 *
 * <p>{@code MATCHED} means the event's payment intent id resolved to an
 * existing order's {@code payment_reference}; {@code UNMATCHED} means it did
 * not (e.g. the intent belongs to no order yet, or was created outside the
 * usual checkout flow). This is a reconciliation log, not an authorization
 * mechanism — order confirmation still happens via the existing server-side
 * {@code PaymentGatewayService.verifyPayment} check at order-creation time;
 * an {@code UNMATCHED} row does not roll anything back, it only flags the
 * mismatch for investigation.</p>
 */
public enum WebhookEventStatus {
    MATCHED,
    UNMATCHED
}
