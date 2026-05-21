package com.walmal.warehouse.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.application.InventoryAdjustmentService;
import com.walmal.order.application.OrderFulfillmentService;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.domain.OrderStatus;
import com.walmal.warehouse.application.WarehouseFulfillmentService;
import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import com.walmal.warehouse.application.dto.FulfillmentLineDto;
import com.walmal.warehouse.application.dto.ShipmentDto;
import com.walmal.warehouse.config.WarehouseRabbitMQConfig;
import com.walmal.warehouse.domain.FulfillmentLine;
import com.walmal.warehouse.domain.FulfillmentOrder;
import com.walmal.warehouse.domain.FulfillmentStatus;
import com.walmal.warehouse.domain.Shipment;
import com.walmal.warehouse.domain.event.FulfillmentCancelledEvent;
import com.walmal.warehouse.domain.event.FulfillmentShippedEvent;
import com.walmal.warehouse.infrastructure.FulfillmentLineRepository;
import com.walmal.warehouse.infrastructure.FulfillmentOrderRepository;
import com.walmal.warehouse.infrastructure.ShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link WarehouseFulfillmentService}.
 *
 * <p>AUDIT RULE: All destructive DB operations (status→CANCELLED, WRITE_OFF stock adjustments)
 * call {@code auditService.log(...)} BEFORE the mutation. This is a hard architecture rule.
 * Specifically on the {@link #shipFulfillment} path: for each discrepant line,
 * audit is written BEFORE {@code inventoryAdjustmentService.adjustStock()} is called.</p>
 *
 * <p>DIP compliance: RabbitMQ via {@code DomainEventPublisher}, audit via {@code AuditService}.
 * No direct framework classes in business logic.</p>
 */
@Service
public class WarehouseFulfillmentServiceImpl implements WarehouseFulfillmentService {

    private final FulfillmentOrderRepository fulfillmentRepo;
    private final FulfillmentLineRepository lineRepo;
    private final ShipmentRepository shipmentRepo;
    private final OrderFulfillmentService orderFulfillmentService;
    private final OrderQueryService orderQueryService;
    private final InventoryAdjustmentService inventoryAdjustmentService;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;

    public WarehouseFulfillmentServiceImpl(
            FulfillmentOrderRepository fulfillmentRepo,
            FulfillmentLineRepository lineRepo,
            ShipmentRepository shipmentRepo,
            OrderFulfillmentService orderFulfillmentService,
            OrderQueryService orderQueryService,
            InventoryAdjustmentService inventoryAdjustmentService,
            AuditService auditService,
            DomainEventPublisher eventPublisher) {
        this.fulfillmentRepo = fulfillmentRepo;
        this.lineRepo = lineRepo;
        this.shipmentRepo = shipmentRepo;
        this.orderFulfillmentService = orderFulfillmentService;
        this.orderQueryService = orderQueryService;
        this.inventoryAdjustmentService = inventoryAdjustmentService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    // ── getFulfillment ────────────────────────────────────────────────────────

    @Override
    public FulfillmentDetailDto getFulfillment(UUID orderId) {
        FulfillmentOrder fulfillment = requireFulfillmentByOrderId(orderId);
        List<FulfillmentLine> lines = lineRepo.findByFulfillmentOrder(fulfillment);
        Optional<Shipment> shipment = shipmentRepo.findByFulfillmentOrder(fulfillment);
        OrderStatus orderStatus = orderQueryService.getOrderStatus(orderId);
        return toDetailDto(fulfillment, lines, shipment.orElse(null), orderStatus);
    }

    // ── advanceStatus ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void advanceStatus(UUID orderId, FulfillmentStatus targetStatus, String notes) {
        if (targetStatus == FulfillmentStatus.SHIPPED) {
            throw new BusinessRuleException(
                    "Use shipFulfillment() to advance to SHIPPED status — carrier and tracking number required");
        }
        FulfillmentOrder fulfillment = requireFulfillmentByOrderId(orderId);
        fulfillment.advanceTo(targetStatus, notes);
        fulfillmentRepo.save(fulfillment);
    }

    // ── shipFulfillment ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public void shipFulfillment(UUID orderId, String carrier, String trackingNumber, String notes) {
        FulfillmentOrder fulfillment = requireFulfillmentByOrderId(orderId);

        // Validate carrier/trackingNumber up front
        if (carrier == null || carrier.isBlank()) {
            throw new BusinessRuleException("Carrier is required to ship a fulfillment");
        }
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new BusinessRuleException("Tracking number is required to ship a fulfillment");
        }

        // Write WRITE_OFF adjustments for discrepant lines.
        // ARCHITECTURE RULE: auditService.log() BEFORE inventoryAdjustmentService.adjustStock()
        List<FulfillmentLine> lines = lineRepo.findByFulfillmentOrder(fulfillment);
        for (FulfillmentLine line : lines) {
            if (line.hasDiscrepancy()) {
                int writeOff = line.getDiscrepancy();

                // Audit BEFORE mutation
                auditService.log(new AuditEntry(
                        "warehouse_fulfillment_lines",
                        line.getId(),
                        AuditAction.UPDATE,
                        "{\"quantityRequested\":" + line.getQuantityRequested()
                                + ",\"quantityPicked\":" + line.getQuantityPicked() + "}",
                        "{\"writeOff\":" + writeOff + ",\"reason\":\"fulfillment-discrepancy\"}",
                        "system:warehouse-ship"));

                // Mutation — called AFTER audit (architecture rule)
                inventoryAdjustmentService.adjustStock(
                        line.getVariantId(),
                        line.getLocationId(),
                        -writeOff,
                        "Fulfillment write-off for order " + orderId
                                + " (SKU: " + line.getSkuSnapshot() + ")",
                        "system:warehouse");
            }
        }

        // Advance domain state PACKED → SHIPPED
        fulfillment.advanceTo(FulfillmentStatus.SHIPPED, notes);
        fulfillmentRepo.save(fulfillment);

        // Create shipment record
        Shipment shipment = new Shipment(fulfillment, carrier, trackingNumber, Instant.now());
        shipmentRepo.save(shipment);

        // Notify Order module — transitions order CONFIRMED → FULFILLED
        orderFulfillmentService.markFulfilled(fulfillment.getOrderId());

        // Publish downstream event for Notification module
        eventPublisher.publish(
                new FulfillmentShippedEvent(fulfillment.getOrderId(), fulfillment.getUserId(),
                        carrier, trackingNumber, Instant.now()),
                WarehouseRabbitMQConfig.RK_FULFILLMENT_SHIPPED);
    }

    // ── recordPickedQuantity ──────────────────────────────────────────────────

    @Override
    @Transactional
    public void recordPickedQuantity(UUID lineId, int quantityPicked) {
        FulfillmentLine line = lineRepo.findById(lineId)
                .orElseThrow(() -> new ResourceNotFoundException("FulfillmentLine", lineId));

        if (line.getFulfillmentOrder().getStatus() != FulfillmentStatus.PICKING) {
            throw new BusinessRuleException(
                    "Can only record picked quantity while fulfillment is in PICKING status. "
                    + "Current status: " + line.getFulfillmentOrder().getStatus());
        }
        if (quantityPicked < 0 || quantityPicked > line.getQuantityRequested()) {
            throw new BusinessRuleException(
                    "Quantity picked must be between 0 and " + line.getQuantityRequested());
        }

        line.setQuantityPicked(quantityPicked);
        lineRepo.save(line);
    }

    // ── cancelFulfillment ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public void cancelFulfillment(UUID orderId) {
        Optional<FulfillmentOrder> fulfillmentOpt = fulfillmentRepo.findByOrderId(orderId);
        if (fulfillmentOpt.isEmpty()) {
            // Idempotent: order may not have reached warehouse yet
            return;
        }

        FulfillmentOrder fulfillment = fulfillmentOpt.get();
        if (!fulfillment.isCancellable()) {
            throw new BusinessRuleException(
                    "Fulfillment cannot be cancelled in status: " + fulfillment.getStatus()
                    + ". Only PENDING or PICKING fulfillments may be cancelled.");
        }

        // Audit BEFORE mutation (architecture rule)
        auditService.log(new AuditEntry(
                "warehouse_fulfillments",
                fulfillment.getId(),
                AuditAction.STATUS_CHANGE,
                "{\"status\":\"" + fulfillment.getStatus().name() + "\"}",
                "{\"status\":\"CANCELLED\"}",
                "system:order-cancelled"));

        fulfillment.cancel();
        fulfillmentRepo.save(fulfillment);

        eventPublisher.publish(
                new FulfillmentCancelledEvent(orderId, fulfillment.getUserId(), "ORDER_CANCELLED"),
                WarehouseRabbitMQConfig.RK_FULFILLMENT_CANCELLED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FulfillmentOrder requireFulfillmentByOrderId(UUID orderId) {
        return fulfillmentRepo.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Fulfillment", orderId));
    }

    private FulfillmentDetailDto toDetailDto(FulfillmentOrder f, List<FulfillmentLine> lines,
                                              Shipment shipment, OrderStatus orderStatus) {
        List<FulfillmentLineDto> lineDtos = lines.stream()
                .map(l -> new FulfillmentLineDto(
                        l.getId(), l.getVariantId(), l.getLocationId(),
                        l.getSkuSnapshot(), l.getQuantityRequested(),
                        l.getQuantityPicked(), l.getDiscrepancy()))
                .collect(Collectors.toList());

        ShipmentDto shipmentDto = shipment == null ? null : new ShipmentDto(
                shipment.getId(), shipment.getCarrier(),
                shipment.getTrackingNumber(), shipment.getShippedAt());

        return new FulfillmentDetailDto(
                f.getId(), f.getOrderId(), f.getUserId(),
                f.getStatus(), orderStatus,
                f.getShippingAddress(), lineDtos, shipmentDto,
                f.getNotes(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
