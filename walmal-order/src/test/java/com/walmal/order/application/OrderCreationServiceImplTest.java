package com.walmal.order.application;

import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.order.application.dto.OrderLineItem;
import com.walmal.order.application.impl.OrderCreationServiceImpl;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.ShippingAddress;
import com.walmal.order.domain.event.OrderCancelledEvent;
import com.walmal.order.domain.event.OrderConfirmedEvent;
import com.walmal.order.domain.event.OrderCreatedEvent;
import com.walmal.order.infrastructure.OrderRepository;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import com.walmal.product.domain.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderCreationServiceImpl}.
 * Verifies audit ordering, event publishing, and cross-module service delegation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCreationServiceImplTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductCatalogService productCatalogService;
    @Mock ProductPricingService productPricingService;
    @Mock InventoryReservationService inventoryReservationService;
    @Mock PaymentGatewayService paymentGatewayService;
    @Mock DomainEventPublisher eventPublisher;
    @Mock AuditService auditService;

    @InjectMocks OrderCreationServiceImpl service;

    private UUID userId;
    private UUID variantId;
    private UUID locationId;
    private ShippingAddress address;
    private List<OrderLineItem> items;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        address = new ShippingAddress("123 Main St", null, "Springfield", "US", "12345");
        items = List.of(new OrderLineItem(variantId, locationId, 2));
    }

    private void setupHappyPath() {
        when(productCatalogService.isVariantActive(variantId)).thenReturn(true);
        when(productCatalogService.findVariantById(variantId)).thenReturn(Optional.of(
                new VariantSummaryDto(variantId, UUID.randomUUID(), "SKU-001", "BC-001",
                        "Test Product", "Red", "M", ProductStatus.ACTIVE)));
        when(productPricingService.getPriceForVariant(variantId)).thenReturn(
                new PriceDto(variantId, BigDecimal.valueOf(49.99), "USD", Instant.now()));
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            // Simulate @PrePersist and ID generation via reflection would be needed,
            // but for unit tests we use a real-ish Order and just return it
            return o;
        });
        when(paymentGatewayService.charge(any(), any(), any())).thenReturn(
                new PaymentResult("PAY-REF-001", PaymentStatus.SUCCESS));
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_variantIsInactive")
    void should_throwBusinessRuleException_when_variantIsInactive() {
        when(productCatalogService.isVariantActive(variantId)).thenReturn(false);

        assertThatThrownBy(() -> service.createOrder(userId, items, address, "USD"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not active");

        verify(orderRepository, never()).save(any());
        verify(inventoryReservationService, never()).reserveStock(any(), any());
    }

    @Test
    @DisplayName("should_publishOrderCreatedEvent_when_orderPersisted")
    void should_publishOrderCreatedEvent_when_orderPersisted() {
        setupHappyPath();

        service.createOrder(userId, items, address, "USD");

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, atLeast(1)).publish(captor.capture(), anyString());

        boolean hasCreatedEvent = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof OrderCreatedEvent);
        org.assertj.core.api.Assertions.assertThat(hasCreatedEvent).isTrue();
    }

    @Test
    @DisplayName("should_publishOrderConfirmedEvent_when_paymentSucceeds")
    void should_publishOrderConfirmedEvent_when_paymentSucceeds() {
        setupHappyPath();

        service.createOrder(userId, items, address, "USD");

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, atLeast(2)).publish(captor.capture(), anyString());

        boolean hasConfirmedEvent = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof OrderConfirmedEvent);
        org.assertj.core.api.Assertions.assertThat(hasConfirmedEvent).isTrue();
    }

    @Test
    @DisplayName("should_publishOrderCancelledEvent_when_paymentFails")
    void should_publishOrderCancelledEvent_when_paymentFails() {
        when(productCatalogService.isVariantActive(variantId)).thenReturn(true);
        when(productCatalogService.findVariantById(variantId)).thenReturn(Optional.of(
                new VariantSummaryDto(variantId, UUID.randomUUID(), "SKU-001", "BC-001",
                        "Test Product", "Red", "M", ProductStatus.ACTIVE)));
        when(productPricingService.getPriceForVariant(variantId)).thenReturn(
                new PriceDto(variantId, BigDecimal.valueOf(49.99), "USD", Instant.now()));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGatewayService.charge(any(), any(), any())).thenReturn(
                new PaymentResult(null, PaymentStatus.FAILED));

        service.createOrder(userId, items, address, "USD");

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, atLeast(1)).publish(captor.capture(), anyString());

        boolean hasCancelledEvent = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof OrderCancelledEvent);
        org.assertj.core.api.Assertions.assertThat(hasCancelledEvent).isTrue();
    }

    @Test
    @DisplayName("should_releaseReservation_when_paymentFails")
    void should_releaseReservation_when_paymentFails() {
        when(productCatalogService.isVariantActive(variantId)).thenReturn(true);
        when(productCatalogService.findVariantById(variantId)).thenReturn(Optional.of(
                new VariantSummaryDto(variantId, UUID.randomUUID(), "SKU-001", "BC-001",
                        "Test Product", "Red", "M", ProductStatus.ACTIVE)));
        when(productPricingService.getPriceForVariant(variantId)).thenReturn(
                new PriceDto(variantId, BigDecimal.valueOf(49.99), "USD", Instant.now()));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGatewayService.charge(any(), any(), any())).thenReturn(
                new PaymentResult(null, PaymentStatus.FAILED));

        service.createOrder(userId, items, address, "USD");

        verify(inventoryReservationService).releaseReservation(any(), any());
        verify(inventoryReservationService, never()).confirmReservation(any());
    }

    @Test
    @DisplayName("should_writeAuditLog_when_cancelOrderCalled")
    void should_writeAuditLog_when_cancelOrderCalled() {
        UUID orderId = UUID.randomUUID();
        Order pendingOrder = new Order(userId, "USD", BigDecimal.valueOf(99.99), address);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InOrder inOrder = inOrder(auditService, orderRepository);

        service.cancelOrder(orderId, userId);

        inOrder.verify(auditService).log(any());
        inOrder.verify(orderRepository).save(any());
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_cancellingConfirmedOrder")
    void should_throwBusinessRuleException_when_cancellingConfirmedOrder() {
        UUID orderId = UUID.randomUUID();
        Order confirmedOrder = new Order(userId, "USD", BigDecimal.valueOf(99.99), address);
        confirmedOrder.confirm("PAY-REF");

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(confirmedOrder));

        assertThatThrownBy(() -> service.cancelOrder(orderId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be cancelled");
    }
}
