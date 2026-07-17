package com.walmal.infrastructure.payment;

import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stub gateway — always returns success. For local dev, demo, and tests ONLY.
 *
 * <p><b>Fail-closed:</b> this bean exists only when {@code walmal.payment.gateway=stub}
 * is explicitly set (the {@code test} profile does so; see application-test.yml).
 * The default/production profile leaves the property unset, so this bean is NOT
 * created and {@code OrderCreationServiceImpl}'s required {@link PaymentGatewayService}
 * dependency is unsatisfied — the app fails fast at startup rather than silently
 * confirming orders on an always-success stub (the payment analogue of shipping a
 * committed JWT key). A real gateway must be wired and selected before production
 * can boot.</p>
 *
 * <p><b>Not the full trust boundary:</b> this only prevents the stub reaching
 * production. Real server-side payment verification (retrieve the Stripe
 * PaymentIntent by reference and assert status=succeeded + amount/currency match,
 * or handle a signature-verified webhook) is still to be built — see the
 * security review's finding #2.</p>
 */
@Service
@Primary
@ConditionalOnProperty(name = "walmal.payment.gateway", havingValue = "stub")
public class StubPaymentGatewayServiceImpl implements PaymentGatewayService {

    @Override
    public PaymentResult charge(UUID orderId, BigDecimal amount, String currency) {
        return new PaymentResult(UUID.randomUUID().toString(), PaymentStatus.SUCCESS);
    }
}
