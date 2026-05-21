package com.walmal.order.application.impl;

import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.application.dto.OrderDetailDto;
import com.walmal.order.application.dto.OrderItemDto;
import com.walmal.order.application.dto.OrderSummaryDto;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.infrastructure.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Implementation of {@link OrderQueryService}.
 *
 * <p>All reads are read-only transactions. Maps domain entities to DTOs;
 * domain entities are never exposed outside this package boundary.</p>
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderQueryServiceImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public OrderDetailDto getOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return toDetailDto(order);
    }

    @Override
    public Page<OrderSummaryDto> listOrdersByUser(UUID userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable)
                .map(this::toSummaryDto);
    }

    @Override
    public OrderStatus getOrderStatus(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return order.getStatus();
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private OrderDetailDto toDetailDto(Order order) {
        List<OrderItemDto> itemDtos = order.getItems().stream()
                .map(item -> new OrderItemDto(
                        item.getVariantId(),
                        item.getProductNameSnapshot(),
                        item.getSkuSnapshot(),
                        item.getQuantity(),
                        item.getPriceAtPurchase(),
                        item.getCurrency(),
                        item.getSubtotal()))
                .toList();

        return new OrderDetailDto(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getShippingAddress(),
                itemDtos,
                order.getCreatedAt());
    }

    private OrderSummaryDto toSummaryDto(Order order) {
        return new OrderSummaryDto(
                order.getId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getCreatedAt());
    }
}
