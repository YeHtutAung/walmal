package com.walmal.infrastructure.payment;

import com.walmal.common.payment.VerifiedWebhookEvent;
import com.walmal.common.payment.WebhookVerificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Unit tests for {@link StripeWebhookVerifierImpl}. Signatures are computed
 * locally with the exact scheme Stripe's SDK verifies
 * ({@code t=<epochSeconds>,v1=hex(HMAC_SHA256(secret, "<epochSeconds>.<payload>"))}),
 * so no network call or Stripe test fixture is needed — this is the same
 * scheme the live-verification curl script in the deployment task uses.
 */
class StripeWebhookVerifierImplTest {

    private static final String SECRET = "whsec_test_secret_for_unit_tests";

    private final StripeWebhookVerifierImpl verifier = new StripeWebhookVerifierImpl(SECRET);

    @Test
    @DisplayName("should_returnVerifiedEvent_when_signatureValid_and_intentMatchesPaymentIntentSucceeded")
    void should_returnVerifiedEvent_when_signatureValid() {
        String payload = paymentIntentSucceededPayload("evt_test_1", "pi_test_123");
        String signatureHeader = sign(payload, SECRET);

        VerifiedWebhookEvent event = verifier.verify(payload, signatureHeader);

        assertThat(event.eventId()).isEqualTo("evt_test_1");
        assertThat(event.eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(event.paymentIntentId()).isEqualTo("pi_test_123");
    }

    @Test
    @DisplayName("should_extractPaymentIntentId_fromRawJson_even_when_eventApiVersionDiffersFromSdk")
    void should_extractFromRawJson_despiteApiVersionMismatch() {
        // api_version deliberately does not match the pinned stripe-java version.
        // Webhook.constructEvent still verifies the HMAC fine (the signature covers
        // the raw bytes, not the api_version), but getDataObjectDeserializer().getObject()
        // would return Optional.empty() here — this is exactly the trap the raw-JSON
        // extraction in StripeWebhookVerifierImpl is designed to avoid.
        String payload = "{"
                + "\"id\":\"evt_test_2\","
                + "\"type\":\"payment_intent.succeeded\","
                + "\"api_version\":\"2016-07-06\","
                + "\"data\":{\"object\":{\"id\":\"pi_test_456\",\"object\":\"payment_intent\",\"status\":\"succeeded\"}}"
                + "}";
        String signatureHeader = sign(payload, SECRET);

        VerifiedWebhookEvent event = verifier.verify(payload, signatureHeader);

        assertThat(event.paymentIntentId()).isEqualTo("pi_test_456");
    }

    @Test
    @DisplayName("should_returnNullPaymentIntentId_when_dataObjectHasNoIdField")
    void should_returnNullPaymentIntentId_when_noIdField() {
        String payload = "{"
                + "\"id\":\"evt_test_3\","
                + "\"type\":\"customer.created\","
                + "\"data\":{\"object\":{\"object\":\"customer\"}}"
                + "}";
        String signatureHeader = sign(payload, SECRET);

        VerifiedWebhookEvent event = verifier.verify(payload, signatureHeader);

        assertThat(event.eventType()).isEqualTo("customer.created");
        assertThat(event.paymentIntentId()).isNull();
    }

    @Test
    @DisplayName("should_throwWebhookVerificationException_when_payloadTamperedAfterSigning")
    void should_throw_when_payloadTampered() {
        String payload = paymentIntentSucceededPayload("evt_test_4", "pi_test_789");
        String signatureHeader = sign(payload, SECRET);
        String tamperedPayload = payload.replace("pi_test_789", "pi_test_999");

        assertThatThrownBy(() -> verifier.verify(tamperedPayload, signatureHeader))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("should_throwWebhookVerificationException_when_signedWithWrongSecret")
    void should_throw_when_wrongSecret() {
        String payload = paymentIntentSucceededPayload("evt_test_5", "pi_test_abc");
        String signatureHeader = sign(payload, "whsec_a_completely_different_secret");

        assertThatThrownBy(() -> verifier.verify(payload, signatureHeader))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("should_throwWebhookVerificationException_when_signatureHeaderMissing")
    void should_throw_when_signatureHeaderNull() {
        String payload = paymentIntentSucceededPayload("evt_test_6", "pi_test_def");

        assertThatThrownBy(() -> verifier.verify(payload, null))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("should_throwWebhookVerificationException_when_signatureHeaderBlank")
    void should_throw_when_signatureHeaderBlank() {
        String payload = paymentIntentSucceededPayload("evt_test_7", "pi_test_ghi");

        assertThatThrownBy(() -> verifier.verify(payload, "   "))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("should_throwIllegalStateException_when_constructedWithBlankWebhookSecret")
    void should_throw_when_constructedWithBlankSecret() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new StripeWebhookVerifierImpl(""))
                .withMessageContaining("STRIPE_WEBHOOK_SECRET");
        assertThatIllegalStateException()
                .isThrownBy(() -> new StripeWebhookVerifierImpl(null));
    }

    @Test
    @DisplayName("should_throwWebhookVerificationException_when_bodyIsNotJson_even_with_wellFormedSignatureHeader")
    void should_reject_when_bodyIsNotJson() {
        // constructEvent deserializes BEFORE verifying the signature, so a
        // garbage body throws an unchecked GSON exception, not a
        // SignatureVerificationException. This pins that the verifier maps it
        // to the 400 path instead of letting it escape as an unauthenticated,
        // rate-limit-exempt 500 (free error-log flooding).
        String garbage = "this is not json";
        String signatureHeader = sign(garbage, SECRET);

        assertThatThrownBy(() -> verifier.verify(garbage, signatureHeader))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("should_rejectStaleTimestamp_beyondDefaultReplayToleranceWindow")
    void should_reject_when_timestampStale() {
        // Stripe's SDK enforces a 300s default replay tolerance. Pin it so a
        // future refactor cannot silently pass tolerance=0 (disabled).
        String payload = paymentIntentSucceededPayload("evt_stale_1", "pi_stale_1");
        long staleTs = Instant.now().minusSeconds(400).getEpochSecond();
        String signatureHeader = "t=" + staleTs + ",v1="
                + hmacSha256Hex(SECRET, staleTs + "." + payload);

        assertThatThrownBy(() -> verifier.verify(payload, signatureHeader))
                .isInstanceOf(WebhookVerificationException.class);
    }

    // ── Test signing helper — mirrors Stripe's Webhook signing scheme ────────

    private static String paymentIntentSucceededPayload(String eventId, String paymentIntentId) {
        return "{"
                + "\"id\":\"" + eventId + "\","
                + "\"type\":\"payment_intent.succeeded\","
                + "\"api_version\":\"2024-06-20\","
                + "\"data\":{\"object\":{\"id\":\"" + paymentIntentId + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\"}}"
                + "}";
    }

    private static String sign(String payload, String secret) {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        String hex = hmacSha256Hex(secret, signedPayload);
        return "t=" + timestamp + ",v1=" + hex;
    }

    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(rawHmac.length * 2);
            for (byte b : rawHmac) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
