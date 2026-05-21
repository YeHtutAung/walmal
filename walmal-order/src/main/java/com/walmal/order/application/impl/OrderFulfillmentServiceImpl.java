package com.walmal.order.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.order.application.OrderFulfillmentService;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.event.OrderFulfilledEvent;
import com.walmal.order.infrastructure.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Implementation of {@link OrderFulfillmentService}.
 *
 * <p>Writes audit_log BEFORE the status mutation (architecture rule).
 * Publishes {@code order.fulfilled} after save.</p>
 */
@Service
public class OrderFulfillmentServiceImpl implements OrderFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentServiceImpl.class);

    private final OrderRepository orderRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public OrderFulfillmentServiceImpl(OrderRepository orderRepository,
                                        DomainEventPublisher eventPublisher,
                                        AuditService auditService) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void markFulfilled(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new com.walmal.common.exception.BusinessRuleException(
                    "Order can only be fulfilled from CONFIRMED status. Current status: " + order.getStatus());
        }

        // Audit BEFORE mutation (architecture rule)
        auditService.log(new AuditEntry(
                "order_orders", orderId, AuditAction.STATUS_CHANGE,
                "{\"status\":\"CONFIRMED\"}",
                "{\"status\":\"FULFILLED\"}",
                "system:fulfillment"));

        order.fulfill();
        orderRepository.save(order);

        eventPublisher.publish(
                new OrderFulfilledEvent(orderId, order.getUserId(), Instant.now()),
                "order.fulfilled");

        log.info("Order fulfilled: orderId={}", orderId);
    }
}
