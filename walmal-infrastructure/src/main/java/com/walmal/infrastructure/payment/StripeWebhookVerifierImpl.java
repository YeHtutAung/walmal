package com.walmal.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import com.walmal.common.payment.PaymentWebhookVerifier;
import com.walmal.common.payment.VerifiedWebhookEvent;
import com.walmal.common.payment.WebhookVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies Stripe webhook deliveries against {@code STRIPE_WEBHOOK_SECRET}.
 *
 * <p>Always registered (unlike {@code StripePaymentGatewayServiceImpl}, which
 * is gated behind {@code walmal.payment.gateway=stripe}): the webhook is a
 * standing reconciliation log independent of which gateway is active for
 * order confirmation, so it needs its own secret and is wired unconditionally
 * — every profile that exposes {@code POST /api/v1/payment/webhook} must
 * supply {@code STRIPE_WEBHOOK_SECRET} (see {@code application-test.yml} for
 * the throwaway test-profile value).</p>
 */
@Component
public class StripeWebhookVerifierImpl implements PaymentWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookVerifierImpl.class);

    private final String webhookSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StripeWebhookVerifierImpl(
            @Value("${walmal.payment.stripe.webhook-secret:}") String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "walmal.payment.stripe.webhook-secret (env STRIPE_WEBHOOK_SECRET) must be set "
                    + "for the Stripe webhook endpoint to verify signatures.");
        }
        this.webhookSecret = webhookSecret;
    }

    @Override
    public VerifiedWebhookEvent verify(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookVerificationException("Missing Stripe-Signature header");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            throw new WebhookVerificationException("Invalid Stripe webhook signature", e);
        } catch (RuntimeException e) {
            // constructEvent deserializes the payload BEFORE verifying the
            // signature (confirmed against stripe-java 28.2 bytecode), so a
            // garbage non-JSON body throws GSON's unchecked JsonSyntaxException.
            // Without this catch that escaped to the global 500 handler on an
            // unauthenticated, rate-limit-exempt path — free error-log flooding
            // for anyone on the internet. A body Stripe could never have signed
            // is a client error: 400.
            log.warn("Stripe webhook payload rejected before signature check: {}", e.getMessage());
            throw new WebhookVerificationException("Malformed Stripe webhook payload", e);
        }

        String paymentIntentId = extractPaymentIntentId(payload);
        return new VerifiedWebhookEvent(event.getId(), event.getType(), paymentIntentId);
    }

    /**
     * Extracts {@code data.object.id} directly from the raw JSON payload string.
     *
     * <p>Deliberately NOT using {@code event.getDataObjectDeserializer().getObject()}
     * here: Stripe's typed deserialization returns an empty {@code Optional}
     * whenever the event's embedded {@code api_version} differs from the
     * SDK's pinned version — common with dashboard-resent events, older
     * account API versions, and any hand-crafted test payload (as this
     * class's own unit tests demonstrate). {@code event.getId()} and
     * {@code event.getType()} are plain top-level string fields, not subject
     * to that polymorphic deserialization trap, so they are read straight
     * off the {@link Event} object above.</p>
     *
     * @return the payment intent id, or {@code null} if the payload has no
     *         {@code data.object.id} (e.g. non-payment-intent event types)
     */
    private String extractPaymentIntentId(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode idNode = root.path("data").path("object").path("id");
            return idNode.isMissingNode() || idNode.isNull() ? null : idNode.asText();
        } catch (JsonProcessingException e) {
            // constructEvent already proved payload is valid JSON Stripe accepted as
            // signed; this is defensive only and should be unreachable in practice.
            log.warn("Failed to parse webhook payload for payment_intent id extraction: {}", e.getMessage());
            return null;
        }
    }
}
