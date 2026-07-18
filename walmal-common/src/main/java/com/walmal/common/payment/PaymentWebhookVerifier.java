package com.walmal.common.payment;

/**
 * Abstraction over payment-gateway webhook signature verification. Business
 * logic (the order module's reconciliation service) depends on this
 * interface only — never on a concrete gateway SDK.
 *
 * <p>DIP: mirrors {@link PaymentGatewayService}. The concrete implementation
 * (Stripe {@code Webhook.constructEvent}) is registered in
 * walmal-infrastructure and wired in walmal-app. walmal-order does not, and
 * must not, depend on walmal-infrastructure or any gateway SDK — this
 * interface is the entire surface it sees.</p>
 */
public interface PaymentWebhookVerifier {

    /**
     * Verifies {@code signatureHeader} against the exact raw {@code payload}
     * bytes using the configured webhook secret, and extracts the fields the
     * caller needs for reconciliation.
     *
     * @param payload         the raw request body exactly as received —
     *                        signatures are computed over these exact bytes;
     *                        any re-serialization (e.g. parse-then-re-emit)
     *                        invalidates the signature
     * @param signatureHeader the value of the gateway's signature header
     *                        (Stripe: {@code Stripe-Signature}); may be null
     *                        or blank, which is itself a verification failure
     * @return the verified event's id, type, and (if present) payment intent id
     * @throws WebhookVerificationException if the signature is missing,
     *                                       malformed, or does not match
     *                                       {@code payload}
     */
    VerifiedWebhookEvent verify(String payload, String signatureHeader);
}
