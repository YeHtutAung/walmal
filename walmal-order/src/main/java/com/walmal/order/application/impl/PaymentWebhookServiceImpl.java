package com.walmal.order.application.impl;

import com.walmal.common.payment.PaymentWebhookVerifier;
import com.walmal.common.payment.VerifiedWebhookEvent;
import com.walmal.order.application.PaymentWebhookService;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.WebhookEventStatus;
import com.walmal.order.infrastructure.OrderRepository;
import com.walmal.order.infrastructure.PaymentWebhookEventStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentWebhookServiceImpl implements PaymentWebhookService {

    /**
     * Event types this reconciliation log records. Anything else is
     * acknowledged (200) and dropped without a database write — Stripe
     * accounts emit dozens of event types the order module has no use for.
     */
    private static final Set<String> HANDLED_EVENT_TYPES =
            Set.of("payment_intent.succeeded", "payment_intent.payment_failed");

    private final PaymentWebhookVerifier verifier;
    private final PaymentWebhookEventStore eventStore;
    private final OrderRepository orderRepository;

    public PaymentWebhookServiceImpl(PaymentWebhookVerifier verifier,
                                      PaymentWebhookEventStore eventStore,
                                      OrderRepository orderRepository) {
        this.verifier = verifier;
        this.eventStore = eventStore;
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void handle(String payload, String signatureHeader) {
        // Throws WebhookVerificationException on a bad/missing signature — propagates
        // to the controller's exception handler as 400, and nothing below runs.
        VerifiedWebhookEvent event = verifier.verify(payload, signatureHeader);

        if (!HANDLED_EVENT_TYPES.contains(event.eventType())) {
            return;
        }

        // A null paymentIntentId (handled type with no data.object.id, unexpected but
        // not impossible) must not become a `WHERE payment_reference IS NULL` lookup —
        // that predicate would spuriously match any never-confirmed order.
        Optional<Order> matchedOrder = event.paymentIntentId() == null
                ? Optional.empty()
                : orderRepository.findFirstByPaymentReference(event.paymentIntentId());
        // Classify on Optional presence, not on the extracted id being non-null —
        // an Order's id is only assigned by JPA at persist time, so keying the
        // MATCHED/UNMATCHED decision off Optional#isPresent() rather than off
        // Order::getId keeps this correct even for entities not yet flushed.
        UUID matchedOrderId = matchedOrder.map(Order::getId).orElse(null);
        WebhookEventStatus status = matchedOrder.isPresent()
                ? WebhookEventStatus.MATCHED
                : WebhookEventStatus.UNMATCHED;

        eventStore.insertIfAbsent(event.eventId(), event.eventType(), event.paymentIntentId(),
                matchedOrderId, status);
    }
}
