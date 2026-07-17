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
     * Prefix marking a payment reference as an in-store POS terminal sale. Payment
     * for these was captured at the terminal (cash / card reader), so it is
     * terminal-authoritative and not verified against the web gateway — a real
     * gateway impl treats a reference with this prefix as already-paid.
     */
    String POS_TERMINAL_REFERENCE_PREFIX = "pos-terminal:";

    /**
     * Verifies that a payment the client already made covers this order, server-side.
     *
     * <p>The web checkout confirms the payment on the client (e.g. Stripe
     * {@code confirmCardPayment}) and passes the resulting reference here; the
     * backend must never trust that the client actually paid, or paid the right
     * amount. A real implementation retrieves the payment by {@code paymentReference}
     * from the provider and asserts it succeeded and its amount/currency match the
     * server-computed order total before the order is confirmed.</p>
     *
     * @param orderId          the order being paid for
     * @param paymentReference provider payment reference (e.g. a Stripe PaymentIntent
     *                         id), or a {@link #POS_TERMINAL_REFERENCE_PREFIX} marker
     *                         for terminal-authoritative POS sales; must be non-blank
     * @param amount           server-computed order total to reconcile against
     * @param currency         ISO-4217 currency code (e.g., "USD")
     * @return {@link PaymentResult} with the verified reference and outcome status
     */
    PaymentResult verifyPayment(UUID orderId, String paymentReference, BigDecimal amount, String currency);
}
