package com.walmal.order.api;

import com.walmal.common.payment.WebhookVerificationException;
import com.walmal.order.application.PaymentWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Receives Stripe webhook deliveries. Base path: {@code /api/v1/payment}.
 *
 * <p><b>Reconciliation, not authorization:</b> this endpoint does not confirm
 * orders — {@code OrderCreationServiceImpl} already verified the payment
 * server-side, synchronously, at order-creation time via
 * {@code PaymentGatewayService.verifyPayment}. This webhook only records an
 * audit trail of what Stripe reports asynchronously, flagging any event whose
 * payment intent doesn't match a known order ({@code UNMATCHED}) for
 * investigation.</p>
 *
 * <p>Public by design ({@code permitAll} in {@code AuthSecurityConfig}) and
 * exempt from the request-rate limiter — the Stripe signature verified
 * inside {@link PaymentWebhookService#handle} is the actual authentication
 * for this request, and Stripe's automatic retries on non-2xx responses
 * must never be throttled into false failures.</p>
 */
@RestController
@RequestMapping("/api/v1/payment")
@Tag(name = "Payment Webhook", description = "Stripe webhook receiver — signature-verified reconciliation log")
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    public PaymentWebhookController(PaymentWebhookService paymentWebhookService) {
        this.paymentWebhookService = paymentWebhookService;
    }

    @PostMapping("/webhook")
    @Operation(summary = "Stripe webhook receiver",
               description = "Verifies the Stripe-Signature header and records a reconciliation row "
                             + "for payment_intent.succeeded / payment_intent.payment_failed events. "
                             + "200 for any recognized signature (including unhandled event types, which "
                             + "are acknowledged and dropped); 400 for a missing or invalid signature.")
    public ResponseEntity<Void> handleWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "Stripe-Signature", required = false) String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            // Read via the verifier's own exception type so the controller has exactly
            // one failure path (missing vs. invalid signature) mapped by one handler.
            throw new WebhookVerificationException("Missing Stripe-Signature header");
        }

        String payload = readRawBody(request);
        paymentWebhookService.handle(payload, signatureHeader);
        return ResponseEntity.ok().build();
    }

    /**
     * Reads the request body as raw bytes rather than relying on
     * {@code @RequestBody String} + Spring's message-converter selection —
     * the Stripe signature is computed over the exact bytes sent, so this
     * avoids any ambiguity about which {@code HttpMessageConverter} handled
     * (or re-encoded) the body for a given {@code Content-Type}.
     */
    private String readRawBody(HttpServletRequest request) {
        try {
            return StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read webhook request body", e);
        }
    }
}
