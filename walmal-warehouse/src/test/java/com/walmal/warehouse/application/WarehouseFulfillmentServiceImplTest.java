package com.walmal.warehouse.application;

import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.application.InventoryAdjustmentService;
import com.walmal.order.application.OrderFulfillmentService;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.domain.OrderStatus;
import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import com.walmal.warehouse.application.impl.WarehouseFulfillmentServiceImpl;
import com.walmal.warehouse.domain.FulfillmentLine;
import com.walmal.warehouse.domain.FulfillmentOrder;
import com.walmal.warehouse.domain.FulfillmentStatus;
import com.walmal.warehouse.domain.Shipment;
import com.walmal.warehouse.domain.event.FulfillmentCancelledEvent;
import com.walmal.warehouse.domain.event.FulfillmentShippedEvent;
import com.walmal.warehouse.infrastructure.FulfillmentLineRepository;
import com.walmal.warehouse.infrastructure.FulfillmentOrderRepository;
import com.walmal.warehouse.infrastructure.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WarehouseFulfillmentServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseFulfillmentServiceImplTest {

    @Mock FulfillmentOrderRepository fulfillmentRepo;
    @Mock FulfillmentLineRepository lineRepo;
    @Mock ShipmentRepository shipmentRepo;
    @Mock OrderFulfillmentService orderFulfillmentService;
    @Mock OrderQueryService orderQueryService;
    @Mock InventoryAdjustmentService inventoryAdjustmentService;
    @Mock AuditService auditService;
    @Mock DomainEventPublisher eventPublisher;

    @InjectMocks WarehouseFulfillmentServiceImpl service;

    private UUID orderId;
    private UUID lineId;
    private FulfillmentOrder fulfillment;
    private FulfillmentLine lineNoDiscrepancy;
    private FulfillmentLine lineWithDiscrepancy;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        lineId = UUID.randomUUID();

        fulfillment = new FulfillmentOrder(orderId, UUID.randomUUID(), "{}");

        lineNoDiscrepancy = new FulfillmentLine(fulfillment, UUID.randomUUID(), UUID.randomUUID(), "SKU-001", 2);
        lineNoDiscrepancy.setQuantityPicked(2);

        lineWithDiscrepancy = new FulfillmentLine(fulfillment, UUID.randomUUID(), UUID.randomUUID(), "SKU-002", 3);
        lineWithDiscrepancy.setQuantityPicked(2);  // 1 short

        when(fulfillmentRepo.findByOrderId(orderId)).thenReturn(Optional.of(fulfillment));
        when(fulfillmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(shipmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderQueryService.getOrderStatus(orderId)).thenReturn(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("should_returnFulfillmentDetail_when_getFulfillmentCalled")
    void should_returnFulfillmentDetail_when_getFulfillmentCalled() {
        when(lineRepo.findByFulfillmentOrder(fulfillment)).thenReturn(List.of(lineNoDiscrepancy));
        when(shipmentRepo.findByFulfillmentOrder(fulfillment)).thenReturn(Optional.empty());

        FulfillmentDetailDto detail = service.getFulfillment(orderId);

        assertThat(detail.orderId()).isEqualTo(orderId);
        assertThat(detail.status()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(detail.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(detail.lines()).hasSize(1);
        assertThat(detail.shipment()).isNull();
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_fulfillmentNotFound")
    void should_throwResourceNotFoundException_when_fulfillmentNotFound() {
        when(fulfillmentRepo.findByOrderId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFulfillment(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("should_advanceStatusToPicking_when_fulfillmentIsPending")
    void should_advanceStatusToPicking_when_fulfillmentIsPending() {
        service.advanceStatus(orderId, FulfillmentStatus.PICKING, "Picker: John");

        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.PICKING);
        verify(fulfillmentRepo).save(fulfillment);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_advancingToShippedViaAdvanceStatus")
    void should_throwBusinessRuleException_when_advancingToShippedViaAdvanceStatus() {
        assertThatThrownBy(() -> service.advanceStatus(orderId, FulfillmentStatus.SHIPPED, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("shipFulfillment");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_invalidStateTransition")
    void should_throwBusinessRuleException_when_invalidStateTransition() {
        // PENDING → PACKED is invalid (must go PENDING→PICKING→PACKED)
        assertThatThrownBy(() -> service.advanceStatus(orderId, FulfillmentStatus.PACKED, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("should_shipSuccessfully_when_noDiscrepancies")
    void should_shipSuccessfully_when_noDiscrepancies() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);

        when(lineRepo.findByFulfillmentOrder(fulfillment)).thenReturn(List.of(lineNoDiscrepancy));

        service.shipFulfillment(orderId, "FedEx", "TRK-12345", "Shipped");

        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.SHIPPED);
        verify(inventoryAdjustmentService, never()).adjustStock(any(), any(), anyInt(), any(), any());
        verify(orderFulfillmentService).markFulfilled(orderId);
        verify(shipmentRepo).save(any(Shipment.class));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture(), eq("warehouse.fulfillment.shipped"));
        assertThat(eventCaptor.getValue()).isInstanceOf(FulfillmentShippedEvent.class);
    }

    @Test
    @DisplayName("should_writeAuditBeforeWriteOff_when_discrepancyExists")
    void should_writeAuditBeforeWriteOff_when_discrepancyExists() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);

        when(lineRepo.findByFulfillmentOrder(fulfillment)).thenReturn(List.of(lineWithDiscrepancy));

        service.shipFulfillment(orderId, "DHL", "DHL-999", null);

        // InOrder ensures audit is written BEFORE adjustStock (architecture rule)
        InOrder inOrder = inOrder(auditService, inventoryAdjustmentService);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(inventoryAdjustmentService).adjustStock(
                eq(lineWithDiscrepancy.getVariantId()),
                eq(lineWithDiscrepancy.getLocationId()),
                eq(-1),
                contains("write-off"),
                eq("system:warehouse"));
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_shippingWithBlankCarrier")
    void should_throwBusinessRuleException_when_shippingWithBlankCarrier() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);

        assertThatThrownBy(() -> service.shipFulfillment(orderId, "", "TRK-001", null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Carrier");
    }

    @Test
    @DisplayName("should_callMarkFulfilled_when_shipped")
    void should_callMarkFulfilled_when_shipped() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);
        when(lineRepo.findByFulfillmentOrder(fulfillment)).thenReturn(List.of());

        service.shipFulfillment(orderId, "UPS", "UPS-001", null);

        verify(orderFulfillmentService).markFulfilled(orderId);
    }

    @Test
    @DisplayName("should_recordPickedQuantity_when_fulfillmentIsInPicking")
    void should_recordPickedQuantity_when_fulfillmentIsInPicking() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        when(lineRepo.findById(lineId)).thenReturn(Optional.of(lineNoDiscrepancy));

        service.recordPickedQuantity(lineId, 1);

        assertThat(lineNoDiscrepancy.getQuantityPicked()).isEqualTo(1);
        verify(lineRepo).save(lineNoDiscrepancy);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_recordingPickedQty_outsidePickingPhase")
    void should_throwBusinessRuleException_when_recordingPickedQty_outsidePickingPhase() {
        // fulfillment is still PENDING
        when(lineRepo.findById(lineId)).thenReturn(Optional.of(lineNoDiscrepancy));

        assertThatThrownBy(() -> service.recordPickedQuantity(lineId, 1))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PICKING");
    }

    @Test
    @DisplayName("should_cancelFulfillment_when_pending")
    void should_cancelFulfillment_when_pending() {
        service.cancelFulfillment(orderId);

        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
        verify(auditService).log(any(AuditEntry.class));
        verify(fulfillmentRepo).save(fulfillment);
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(captor.capture(), eq("warehouse.fulfillment.cancelled"));
        assertThat(captor.getValue()).isInstanceOf(FulfillmentCancelledEvent.class);
    }

    @Test
    @DisplayName("should_writeAuditBeforeCancelMutation")
    void should_writeAuditBeforeCancelMutation() {
        service.cancelFulfillment(orderId);

        InOrder inOrder = inOrder(auditService, fulfillmentRepo);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(fulfillmentRepo).save(any(FulfillmentOrder.class));
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_cancellingPackedFulfillment")
    void should_throwBusinessRuleException_when_cancellingPackedFulfillment() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);

        assertThatThrownBy(() -> service.cancelFulfillment(orderId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PACKED");
    }

    @Test
    @DisplayName("should_doNothing_when_cancelCalledAndFulfillmentDoesNotExist")
    void should_doNothing_when_cancelCalledAndFulfillmentDoesNotExist() {
        when(fulfillmentRepo.findByOrderId(any())).thenReturn(Optional.empty());

        service.cancelFulfillment(UUID.randomUUID());

        verifyNoInteractions(auditService);
        verifyNoInteractions(eventPublisher);
    }
}
