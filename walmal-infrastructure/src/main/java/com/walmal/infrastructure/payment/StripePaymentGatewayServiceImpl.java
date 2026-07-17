package com.walmal.infrastructure.payment;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Real payment verification against Stripe. Active only when
 * {@code walmal.payment.gateway=stripe} (production).
 *
 * <p>The web client confirms the card payment itself (Stripe
 * {@code confirmCardPayment}) and passes the resulting PaymentIntent id as the
 * order's {@code paymentReference}. This service retrieves that PaymentIntent
 * server-side and confirms the order only if Stripe reports it {@code succeeded}
 * AND its amount + currency match the server-computed total — so a client cannot
 * get an order confirmed by claiming a payment it did not make, or underpaying in
 * a cheaper currency.</p>
 *
 * <p>POS terminal sales are terminal-authoritative (payment captured at the
 * card reader), so a {@link #POS_TERMINAL_REFERENCE_PREFIX} reference is accepted
 * without a Stripe call.</p>
 */
@Service
@ConditionalOnProperty(name = "walmal.payment.gateway", havingValue = "stripe")
public class StripePaymentGatewayServiceImpl implements PaymentGatewayService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGatewayServiceImpl.class);
    private static final String SUCCEEDED = "succeeded";

    public StripePaymentGatewayServiceImpl(
            @Value("${walmal.payment.stripe.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "walmal.payment.stripe.secret-key (env STRIPE_SECRET_KEY) must be set "
                    + "when walmal.payment.gateway=stripe");
        }
        Stripe.apiKey = secretKey;
    }

    @Override
    public PaymentResult verifyPayment(UUID orderId, String paymentReference, BigDecimal amount, String currency) {
        // POS terminal sale — payment already captured at the terminal.
        if (paymentReference.startsWith(POS_TERMINAL_REFERENCE_PREFIX)) {
            return new PaymentResult(paymentReference, PaymentStatus.SUCCESS);
        }

        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentReference);
            PaymentStatus status = assess(
                    intent.getStatus(), intent.getAmount(), intent.getCurrency(), amount, currency);
            if (status != PaymentStatus.SUCCESS) {
                log.warn("Payment verification failed: order={} ref={} intentStatus={} intentAmount={} "
                                + "intentCurrency={} expectedAmount={} expectedCurrency={}",
                        orderId, paymentReference, intent.getStatus(), intent.getAmount(),
                        intent.getCurrency(), amount, currency);
            }
            return new PaymentResult(paymentReference, status);
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent retrieval failed: order={} ref={}: {}",
                    orderId, paymentReference, e.getMessage());
            return new PaymentResult(paymentReference, PaymentStatus.FAILED);
        }
    }

    /**
     * Pure verification: SUCCESS only if the intent succeeded and its amount (in
     * minor units) and currency match the server-computed expectation. Package-private
     * so the reconciliation rules can be unit-tested without a live Stripe call.
     */
    static PaymentStatus assess(String intentStatus, Long intentAmountMinor, String intentCurrency,
                                BigDecimal expectedAmount, String expectedCurrency) {
        if (!SUCCEEDED.equals(intentStatus)) {
            return PaymentStatus.FAILED;
        }
        if (intentCurrency == null || expectedCurrency == null
                || !intentCurrency.equalsIgnoreCase(expectedCurrency)) {
            return PaymentStatus.FAILED;
        }
        if (intentAmountMinor == null || expectedAmount == null) {
            return PaymentStatus.FAILED;
        }
        final long expectedMinor;
        try {
            // ISO-4217 minor units for the 2-decimal currencies this platform supports.
            expectedMinor = expectedAmount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            // Total is not a whole number of minor units — treat as a mismatch.
            return PaymentStatus.FAILED;
        }
        return intentAmountMinor == expectedMinor ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
    }
}
