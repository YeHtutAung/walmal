package com.walmal.common.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Abstraction over external payment gateway. Business logic depends on this interface
 * only — never on a concrete gateway SDK.
 *
 * <p>DIP: all business services inject this interface. The concrete implementation is
 * registered in walmal-infrastructure and wired in walmal-app. Swapping payment providers
 * (Stripe, Adyen, etc.) requires only a new implementation class — zero changes to
 * any business service.</p>
 *
 * <p>No refund method is included for MVP. Refund handling will be added in a future
 * migration when the payment lifecycle requires multi-attempt or partial-refund support.</p>
 */
public interface PaymentGatewayService {

    /**
     * Charges the customer's default payment method for the given order amount.
     *
     * @param orderId   the order UUID used as idempotency key with the gateway
     * @param amount    total charge amount; must be positive
     * @param currency  ISO-4217 currency code (e.g., "USD")
     * @return {@link PaymentResult} with the gateway reference and outcome status
     */
    PaymentResult charge(UUID orderId, BigDecimal amount, String currency);
}
