package com.walmal.order.infrastructure.listener;

import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.ShippingAddress;
import com.walmal.order.infrastructure.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryEventListenerTest {

    @Mock private OrderRepository orderRepository;
    @Mock private AuditService auditService;
    @Mock private DomainEventPublisher eventPublisher;

    @InjectMocks
    private InventoryEventListener listener;

    private UUID orderId;
    private UUID variantId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventListener(orderRepository, auditService, eventPublisher);
        orderId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        locationId = UUID.randomUUID();
    }

    @Test
    @DisplayName("should_cancelOrder_when_conflictReasonIsPOS_PRIORITY")
    void should_cancelOrder_when_conflictReasonIsPOS_PRIORITY() throws Exception {
        Order order = pendingOrder();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenReturn(order);

        listener.handleInventoryReservationReleased(message("POS_PRIORITY"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(auditService).log(any());
        verify(eventPublisher).publish(any(), eq("order.cancelled"));
    }

    @Test
    @DisplayName("should_cancelOrder_when_conflictReasonIsBUFFER_EXHAUSTED")
    void should_cancelOrder_when_conflictReasonIsBUFFER_EXHAUSTED() throws Exception {
        Order order = pendingOrder();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenReturn(order);

        listener.handleInventoryReservationReleased(message("BUFFER_EXHAUSTED"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(eventPublisher).publish(any(), eq("order.cancelled"));
    }

    @Test
    @DisplayName("should_cancelOrder_when_conflictReasonIsEXPIRED")
    void should_cancelOrder_when_conflictReasonIsEXPIRED() throws Exception {
        Order order = pendingOrder();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenReturn(order);

        listener.handleInventoryReservationReleased(message("EXPIRED"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("should_ignore_when_conflictReasonIsCANCELLED")
    void should_ignore_when_conflictReasonIsCANCELLED() throws Exception {
        // CANCELLED means Order itself triggered the release — not an external conflict
        listener.handleInventoryReservationReleased(message("CANCELLED"));

        verifyNoInteractions(orderRepository, auditService, eventPublisher);
    }

    @Test
    @DisplayName("should_discard_when_orderNotFound")
    void should_discard_when_orderNotFound() throws Exception {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        listener.handleInventoryReservationReleased(message("POS_PRIORITY"));

        verify(orderRepository).findById(orderId);
        verifyNoInteractions(auditService, eventPublisher);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_discard_when_orderAlreadyCancelled_idempotency")
    void should_discard_when_orderAlreadyCancelled_idempotency() throws Exception {
        // Simulate receiving a duplicate event after order was already cancelled
        Order order = pendingOrder();
        order.cancel(); // PENDING → CANCELLED

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        listener.handleInventoryReservationReleased(message("POS_PRIORITY"));

        // Must not call cancel again or publish a second event
        verify(orderRepository).findById(orderId);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    @DisplayName("should_discard_when_orderIsConfirmed_noCancelAllowed")
    void should_discard_when_orderIsConfirmed_noCancelAllowed() throws Exception {
        // CONFIRMED orders must not be cancelled via inventory events (ADR-5)
        Order order = pendingOrder();
        order.confirm("PAY-REF-123"); // PENDING → CONFIRMED

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        listener.handleInventoryReservationReleased(message("POS_PRIORITY"));

        verify(orderRepository).findById(orderId);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("should_writeAuditLog_before_cancelMutation")
    void should_writeAuditLog_before_cancelMutation() throws Exception {
        Order order = pendingOrder();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenReturn(order);

        var auditCaptor = ArgumentCaptor.forClass(com.walmal.common.audit.AuditEntry.class);

        listener.handleInventoryReservationReleased(message("EXPIRED"));

        verify(auditService).log(auditCaptor.capture());
        var entry = auditCaptor.getValue();
        assertThat(entry.performedBy()).isEqualTo("system:inventory-event-listener");
        assertThat(entry.newValue()).contains("CANCELLED");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Order pendingOrder() {
        return new Order(
                UUID.randomUUID(),
                "USD",
                BigDecimal.valueOf(99.99),
                new ShippingAddress("1 Main St", null, "Springfield", "US", "12345"));
    }

    private OrderInventoryReleasedMessage message(String conflictReason) {
        return new OrderInventoryReleasedMessage(orderId, variantId, locationId, 1, conflictReason);
    }
}
