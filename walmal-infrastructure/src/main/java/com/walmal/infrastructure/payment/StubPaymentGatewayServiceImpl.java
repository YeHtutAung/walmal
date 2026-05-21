package com.walmal.infrastructure.payment;

import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/** Stub implementation — always returns success. Replace with real gateway for production. */
@Service
@Primary
public class StubPaymentGatewayServiceImpl implements PaymentGatewayService {

    @Override
    public PaymentResult charge(UUID orderId, BigDecimal amount, String currency) {
        return new PaymentResult(UUID.randomUUID().toString(), PaymentStatus.SUCCESS);
    }
}
