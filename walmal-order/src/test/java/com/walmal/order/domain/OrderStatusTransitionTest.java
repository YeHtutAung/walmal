package com.walmal.order.domain;

import com.walmal.common.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Order} state machine transitions.
 */
class OrderStatusTransitionTest {

    private Order freshPendingOrder() {
        return new Order(
                java.util.UUID.randomUUID(),
                "USD",
                java.math.BigDecimal.valueOf(99.99),
                new ShippingAddress("123 Main St", null, "Springfield", "US", "12345"));
    }

    @Test
    @DisplayName("should_transitionToConfirmed_when_confirmedFromPending")
    void should_transitionToConfirmed_when_confirmedFromPending() {
        Order order = freshPendingOrder();
        order.confirm("PAY-REF-001");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentReference()).isEqualTo("PAY-REF-001");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_confirmingNonPendingOrder")
    void should_throwBusinessRuleException_when_confirmingNonPendingOrder() {
        Order order = freshPendingOrder();
        order.confirm("REF-1");

        assertThatThrownBy(() -> order.confirm("REF-2"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    @DisplayName("should_transitionToCancelled_when_cancelledFromPending")
    void should_transitionToCancelled_when_cancelledFromPending() {
        Order order = freshPendingOrder();
        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_cancellingConfirmedOrder")
    void should_throwBusinessRuleException_when_cancellingConfirmedOrder() {
        Order order = freshPendingOrder();
        order.confirm("REF-1");

        assertThatThrownBy(order::cancel)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_cancellingAlreadyCancelled")
    void should_throwBusinessRuleException_when_cancellingAlreadyCancelled() {
        Order order = freshPendingOrder();
        order.cancel();

        assertThatThrownBy(order::cancel)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_cancellingFulfilledOrder")
    void should_throwBusinessRuleException_when_cancellingFulfilledOrder() {
        Order order = freshPendingOrder();
        order.confirm("REF-1");
        order.fulfill();

        assertThatThrownBy(order::cancel)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("FULFILLED");
    }

    @Test
    @DisplayName("should_transitionToFulfilled_when_fulfilledFromConfirmed")
    void should_transitionToFulfilled_when_fulfilledFromConfirmed() {
        Order order = freshPendingOrder();
        order.confirm("REF-1");
        order.fulfill();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_fulfillingPendingOrder")
    void should_throwBusinessRuleException_when_fulfillingPendingOrder() {
        Order order = freshPendingOrder();

        assertThatThrownBy(order::fulfill)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("CONFIRMED");
    }
}
