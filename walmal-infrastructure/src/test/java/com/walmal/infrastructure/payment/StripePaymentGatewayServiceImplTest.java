package com.walmal.infrastructure.payment;

import com.walmal.common.payment.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure Stripe reconciliation logic
 * ({@link StripePaymentGatewayServiceImpl#assess}). The live PaymentIntent
 * retrieval is thin glue over the Stripe SDK and not exercised here.
 */
class StripePaymentGatewayServiceImplTest {

    @Test
    @DisplayName("should_returnSuccess_when_succeeded_and_amount_and_currency_match")
    void should_returnSuccess_when_allMatch() {
        // 49.99 USD -> 4999 minor units
        assertThat(StripePaymentGatewayServiceImpl.assess(
                "succeeded", 4999L, "usd", new BigDecimal("49.99"), "USD"))
                .isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("should_returnFailed_when_intentNotSucceeded")
    void should_returnFailed_when_notSucceeded() {
        assertThat(StripePaymentGatewayServiceImpl.assess(
                "requires_payment_method", 4999L, "usd", new BigDecimal("49.99"), "USD"))
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("should_returnFailed_when_amountUnderpaid")
    void should_returnFailed_when_amountMismatch() {
        // Client paid 4900 (49.00) for a 49.99 order
        assertThat(StripePaymentGatewayServiceImpl.assess(
                "succeeded", 4900L, "usd", new BigDecimal("49.99"), "USD"))
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("should_returnFailed_when_currencyMismatch")
    void should_returnFailed_when_currencyMismatch() {
        // Paid the right number but in MYR for a USD order
        assertThat(StripePaymentGatewayServiceImpl.assess(
                "succeeded", 4999L, "myr", new BigDecimal("49.99"), "USD"))
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("should_matchCurrencyCaseInsensitively")
    void should_matchCurrencyCaseInsensitive() {
        assertThat(StripePaymentGatewayServiceImpl.assess(
                "succeeded", 4999L, "USD", new BigDecimal("49.99"), "usd"))
                .isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("should_returnFailed_when_intentFieldsNull")
    void should_returnFailed_when_nulls() {
        assertThat(StripePaymentGatewayServiceImpl.assess(
                null, null, null, new BigDecimal("49.99"), "USD"))
                .isEqualTo(PaymentStatus.FAILED);
    }
}
