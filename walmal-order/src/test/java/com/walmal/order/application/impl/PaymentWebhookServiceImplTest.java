package com.walmal.order.application.impl;

import com.walmal.common.payment.PaymentWebhookVerifier;
import com.walmal.common.payment.VerifiedWebhookEvent;
import com.walmal.common.payment.WebhookVerificationException;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.ShippingAddress;
import com.walmal.order.domain.WebhookEventStatus;
import com.walmal.order.infrastructure.OrderRepository;
import com.walmal.order.infrastructure.PaymentWebhookEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentWebhookServiceImpl}'s routing logic
 * (matched/unmatched/unhandled-type/duplicate-signaling), with
 * {@link PaymentWebhookVerifier} and {@link PaymentWebhookEventStore} mocked
 * out. Full end-to-end persistence + idempotency behavior is covered by
 * {@code PaymentWebhookIntegrationTest} against a real database.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceImplTest {

    private static final ShippingAddress ADDRESS =
            new ShippingAddress("1 Main St", null, "Springfield", "US", "12345");

    @Mock private PaymentWebhookVerifier verifier;
    @Mock private PaymentWebhookEventStore eventStore;
    @Mock private OrderRepository orderRepository;

    private PaymentWebhookServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PaymentWebhookServiceImpl(verifier, eventStore, orderRepository);
    }

    @Test
    @DisplayName("should_recordMatchedRow_when_paymentIntentSucceeded_and_intentMatchesAnOrder")
    void should_recordMatched_when_intentMatchesOrder() {
        VerifiedWebhookEvent event = new VerifiedWebhookEvent("evt_1", "payment_intent.succeeded", "pi_123");
        when(verifier.verify("raw-payload", "sig")).thenReturn(event);

        Order order = new Order(UUID.randomUUID(), "USD", BigDecimal.TEN, ADDRESS);
        when(orderRepository.findFirstByPaymentReference("pi_123")).thenReturn(Optional.of(order));

        service.handle("raw-payload", "sig");

        verify(eventStore).insertIfAbsent(
                eq("evt_1"), eq("payment_intent.succeeded"), eq("pi_123"),
                eq(order.getId()), eq(WebhookEventStatus.MATCHED));
    }

    @Test
    @DisplayName("should_recordUnmatchedRow_when_intentMatchesNoOrder")
    void should_recordUnmatched_when_noOrderMatches() {
        VerifiedWebhookEvent event = new VerifiedWebhookEvent("evt_2", "payment_intent.succeeded", "pi_orphan");
        when(verifier.verify("raw-payload", "sig")).thenReturn(event);
        when(orderRepository.findFirstByPaymentReference("pi_orphan")).thenReturn(Optional.empty());

        service.handle("raw-payload", "sig");

        verify(eventStore).insertIfAbsent(
                eq("evt_2"), eq("payment_intent.succeeded"), eq("pi_orphan"),
                eq(null), eq(WebhookEventStatus.UNMATCHED));
    }

    @Test
    @DisplayName("should_recordRow_when_paymentIntentFailed_isAHandledType")
    void should_handle_paymentFailedType() {
        VerifiedWebhookEvent event = new VerifiedWebhookEvent("evt_3", "payment_intent.payment_failed", "pi_failed");
        when(verifier.verify(any(), any())).thenReturn(event);
        when(orderRepository.findFirstByPaymentReference("pi_failed")).thenReturn(Optional.empty());

        service.handle("raw-payload", "sig");

        verify(eventStore).insertIfAbsent(
                eq("evt_3"), eq("payment_intent.payment_failed"), eq("pi_failed"),
                eq(null), eq(WebhookEventStatus.UNMATCHED));
    }

    @Test
    @DisplayName("should_notPersist_when_eventTypeIsUnhandled")
    void should_notPersist_when_unhandledEventType() {
        VerifiedWebhookEvent event = new VerifiedWebhookEvent("evt_4", "customer.created", null);
        when(verifier.verify("raw-payload", "sig")).thenReturn(event);

        service.handle("raw-payload", "sig");

        verify(eventStore, never()).insertIfAbsent(any(), any(), any(), any(), any());
        verify(orderRepository, never()).findFirstByPaymentReference(any());
    }

    @Test
    @DisplayName("should_propagate_and_persistNothing_when_signatureInvalid")
    void should_propagateAndPersistNothing_when_signatureInvalid() {
        when(verifier.verify("raw-payload", "bad-sig"))
                .thenThrow(new WebhookVerificationException("Invalid Stripe webhook signature"));

        assertThatThrownBy(() -> service.handle("raw-payload", "bad-sig"))
                .isInstanceOf(WebhookVerificationException.class);

        verifyNoInteractions(eventStore);
        verifyNoInteractions(orderRepository);
    }
}
