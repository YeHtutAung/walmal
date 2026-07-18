package com.walmal.order.application;

import com.walmal.common.payment.WebhookVerificationException;

/**
 * Handles an inbound Stripe webhook delivery: verifies its signature, then
 * records a reconciliation row for {@code payment_intent.succeeded} /
 * {@code payment_intent.payment_failed} events. This is an audit trail, not
 * an authorization path — order confirmation already happened (or didn't)
 * via the synchronous server-side verification in
 * {@code OrderCreationServiceImpl} at order-creation time.
 */
public interface PaymentWebhookService {

    /**
     * @param payload         the raw request body exactly as received
     * @param signatureHeader the {@code Stripe-Signature} header value
     * @throws WebhookVerificationException if the signature is missing,
     *                                       malformed, or invalid — nothing
     *                                       is persisted in that case
     */
    void handle(String payload, String signatureHeader);
}
