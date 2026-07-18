package com.walmal.common.payment;

import com.walmal.common.exception.WalmalException;

/**
 * Thrown by {@link PaymentWebhookVerifier} when a webhook's signature is
 * missing, malformed, or does not match the payload it was sent with.
 *
 * <p>Mapped to {@code 400 Bad Request} by the order module's exception
 * handler — an invalid signature means the request did not come from the
 * gateway, so nothing about it is persisted.</p>
 */
public class WebhookVerificationException extends WalmalException {
    public WebhookVerificationException(String message) { super(message); }
    public WebhookVerificationException(String message, Throwable cause) { super(message, cause); }
}
