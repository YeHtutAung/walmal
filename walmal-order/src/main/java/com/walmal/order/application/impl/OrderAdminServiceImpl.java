package com.walmal.order.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.order.application.OrderAdminService;
import com.walmal.order.application.dto.DailyOrderSummaryDto;
import com.walmal.order.application.dto.OrderAdminSummaryDto;
import com.walmal.order.application.dto.OrderTimeseriesRow;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.event.OrderCancelledEvent;
import com.walmal.order.domain.event.OrderConfirmedEvent;
import com.walmal.order.domain.event.OrderCreatedEvent.OrderItemSnapshot;
import com.walmal.order.domain.event.OrderFulfilledEvent;
import com.walmal.order.infrastructure.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class OrderAdminServiceImpl implements OrderAdminService {

    private final OrderRepository orderRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public OrderAdminServiceImpl(OrderRepository orderRepository,
                                  DomainEventPublisher eventPublisher,
                                  AuditService auditService) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Override
    public Page<OrderAdminSummaryDto> listAllOrders(OrderStatus status, Pageable pageable) {
        Page<Order> page = status != null
                ? orderRepository.findByStatus(status, pageable)
                : orderRepository.findAll(pageable);
        return page
                .map(o -> new OrderAdminSummaryDto(
                        o.getId(), o.getUserId(), o.getStatus(),
                        o.getTotalAmount(), o.getCurrency(),
                        o.getItemCount(), o.getCreatedAt()));
    }

    @Override
    @Transactional
    public void updateStatus(UUID orderId, OrderStatus newStatus, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        OrderStatus current = order.getStatus();
        String auditReason = (reason != null && !reason.isBlank()) ? reason : "admin:status-update";

        auditService.log(new AuditEntry(
                "order_orders", orderId, AuditAction.STATUS_CHANGE,
                "{\"status\":\"" + current + "\"}",
                "{\"status\":\"" + newStatus + "\"}",
                auditReason));

        switch (newStatus) {
            case CONFIRMED -> {
                List<OrderItemSnapshot> snapshots = order.getItems().stream()
                        .map(i -> new OrderItemSnapshot(i.getVariantId(), i.getLocationId(),
                                i.getQuantity(), i.getSkuSnapshot()))
                        .toList();
                order.confirm("admin-override");
                orderRepository.save(order);
                eventPublisher.publish(
                        new OrderConfirmedEvent(orderId, order.getUserId(), snapshots,
                                order.getShippingAddress(), Instant.now()),
                        "order.confirmed");
            }
            case FULFILLED -> {
                order.fulfill();
                orderRepository.save(order);
                eventPublisher.publish(
                        new OrderFulfilledEvent(orderId, order.getUserId(), Instant.now()),
                        "order.fulfilled");
            }
            case CANCELLED -> {
                order.cancel();
                orderRepository.save(order);
                eventPublisher.publish(
                        new OrderCancelledEvent(orderId, order.getUserId(), auditReason, Instant.now()),
                        "order.cancelled");
            }
            default -> throw new BusinessRuleException("Cannot transition order to status: " + newStatus);
        }
    }

    @Override
    public List<DailyOrderSummaryDto> buildDailySummary(List<OrderTimeseriesRow> rows, LocalDate endDateUtc) {
        Map<LocalDate, List<OrderTimeseriesRow>> byDate = rows.stream()
                .collect(Collectors.groupingBy(r -> r.createdAt().atZone(ZoneOffset.UTC).toLocalDate()));

        String currency = rows.stream()
                .filter(r -> r.status() == OrderStatus.FULFILLED)
                .findFirst()
                .map(OrderTimeseriesRow::currency)
                .orElse("USD");

        List<DailyOrderSummaryDto> result = new ArrayList<>();
        for (LocalDate date = endDateUtc.minusDays(29); !date.isAfter(endDateUtc); date = date.plusDays(1)) {
            List<OrderTimeseriesRow> dayRows = byDate.getOrDefault(date, List.of());
            BigDecimal revenue = dayRows.stream()
                    .filter(r -> r.status() == OrderStatus.FULFILLED)
                    .map(OrderTimeseriesRow::totalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            result.add(new DailyOrderSummaryDto(date, dayRows.size(), revenue, currency));
        }
        return result;
    }

    @Override
    public List<DailyOrderSummaryDto> getDailySummary() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant cutoff = today.minusDays(29).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<OrderTimeseriesRow> rows = orderRepository.findForDailySummary(cutoff);
        return buildDailySummary(rows, today);
    }
}
