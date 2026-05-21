package com.walmal.common.payment;

/**
 * Immutable result returned by {@link PaymentGatewayService#charge}.
 *
 * @param paymentReference gateway-assigned reference string; non-null only on SUCCESS
 * @param status           outcome of the charge attempt
 */
public record PaymentResult(String paymentReference, PaymentStatus status) {}
