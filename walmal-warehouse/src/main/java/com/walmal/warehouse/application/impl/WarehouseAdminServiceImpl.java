package com.walmal.warehouse.application.impl;

import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.domain.OrderStatus;
import com.walmal.warehouse.application.WarehouseAdminService;
import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import com.walmal.warehouse.application.dto.FulfillmentLineDto;
import com.walmal.warehouse.application.dto.FulfillmentSummaryDto;
import com.walmal.warehouse.application.dto.ShipmentDto;
import com.walmal.warehouse.domain.FulfillmentLine;
import com.walmal.warehouse.domain.FulfillmentOrder;
import com.walmal.warehouse.domain.Shipment;
import com.walmal.warehouse.infrastructure.FulfillmentLineRepository;
import com.walmal.warehouse.infrastructure.FulfillmentOrderRepository;
import com.walmal.warehouse.infrastructure.ShipmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class WarehouseAdminServiceImpl implements WarehouseAdminService {

    private final FulfillmentOrderRepository fulfillmentRepo;
    private final FulfillmentLineRepository lineRepo;
    private final ShipmentRepository shipmentRepo;
    private final OrderQueryService orderQueryService;

    public WarehouseAdminServiceImpl(FulfillmentOrderRepository fulfillmentRepo,
                                      FulfillmentLineRepository lineRepo,
                                      ShipmentRepository shipmentRepo,
                                      OrderQueryService orderQueryService) {
        this.fulfillmentRepo = fulfillmentRepo;
        this.lineRepo = lineRepo;
        this.shipmentRepo = shipmentRepo;
        this.orderQueryService = orderQueryService;
    }

    @Override
    public Page<FulfillmentSummaryDto> listFulfillments(Pageable pageable) {
        return fulfillmentRepo.findAll(pageable).map(f -> new FulfillmentSummaryDto(
                f.getId(), f.getOrderId(), f.getUserId(),
                f.getStatus(), f.getLineCount(),
                f.getCreatedAt(), f.getUpdatedAt()));
    }

    @Override
    public FulfillmentDetailDto getFulfillmentById(UUID fulfillmentId) {
        FulfillmentOrder fulfillment = fulfillmentRepo.findById(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Fulfillment", fulfillmentId));
        List<FulfillmentLine> lines = lineRepo.findByFulfillmentOrder(fulfillment);
        Optional<Shipment> shipment = shipmentRepo.findByFulfillmentOrder(fulfillment);
        OrderStatus orderStatus = orderQueryService.getOrderStatus(fulfillment.getOrderId());

        List<FulfillmentLineDto> lineDtos = lines.stream()
                .map(l -> new FulfillmentLineDto(
                        l.getId(), l.getVariantId(), l.getLocationId(),
                        l.getSkuSnapshot(), l.getQuantityRequested(),
                        l.getQuantityPicked(), l.getDiscrepancy()))
                .collect(Collectors.toList());

        ShipmentDto shipmentDto = shipment.map(s -> new ShipmentDto(
                s.getId(), s.getCarrier(), s.getTrackingNumber(), s.getShippedAt()))
                .orElse(null);

        return new FulfillmentDetailDto(
                fulfillment.getId(), fulfillment.getOrderId(), fulfillment.getUserId(),
                fulfillment.getStatus(), orderStatus,
                fulfillment.getShippingAddress(), lineDtos, shipmentDto,
                fulfillment.getNotes(), fulfillment.getCreatedAt(), fulfillment.getUpdatedAt());
    }
}
