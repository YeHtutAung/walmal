package com.walmal.order.infrastructure.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.event.OrderCancelledEvent;
import com.walmal.order.infrastructure.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * RabbitMQ listener for inventory reservation-released events.
 *
 * <p>Bound to {@code order.inventory-events.queue} (declared in {@code OrderRabbitMQConfig})
 * which receives messages from {@code inventory.exchange} with routing key
 * {@code inventory.reservation.released}.</p>
 *
 * <p>When a POS conflict or buffer exhaustion forces Inventory to release a web order's
 * reservation, this listener cancels the corresponding Order and publishes
 * {@code order.cancelled} so the Notification module can alert the customer.</p>
 *
 * <p>IMPORTANT: this listener MUST NOT call
 * {@code InventoryReservationService.releaseReservation()} — Inventory has already
 * released the reservation. Calling it again would fail with ResourceNotFoundException.</p>
 *
 * <p>Idempotency guard: if the order is not found or is already CANCELLED, the message
 * is logged and discarded without error — prevents duplicate processing on redelivery.</p>
 */
@Component
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    /** Conflict reasons that require the Order module to cancel the web order. */
    private static final Set<String> CANCEL_TRIGGERS =
            Set.of("POS_PRIORITY", "BUFFER_EXHAUSTED", "EXPIRED");

    private final OrderRepository orderRepository;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public InventoryEventListener(OrderRepository orderRepository,
                                   AuditService auditService,
                                   DomainEventPublisher eventPublisher,
                                   ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "order.inventory-events.queue")
    @Transactional
    public void handleInventoryReservationReleased(String messageBody) {
        OrderInventoryReleasedMessage message;
        try {
            message = objectMapper.readValue(messageBody, OrderInventoryReleasedMessage.class);
        } catch (Exception e) {
            log.error("Failed to deserialize inventory released message: {}", messageBody, e);
            // Do not rethrow — a poison message must not block the queue
            return;
        }

        String conflictReason = message.conflictReason();
        UUID orderId = message.orderId();

        log.debug("Received inventory.reservation.released: orderId={} conflictReason={}",
                orderId, conflictReason);

        if (!CANCEL_TRIGGERS.contains(conflictReason)) {
            // CANCELLED is handled by the order service itself; other reasons are not yet defined
            log.debug("ConflictReason={} does not require order cancellation. Ignoring.", conflictReason);
            return;
        }

        // Idempotency guard: skip if order not found or already cancelled
        Optional<Order> optOrder = orderRepository.findById(orderId);
        if (optOrder.isEmpty()) {
            log.warn("Order not found for inventory released event: orderId={}. Discarding.", orderId);
            return;
        }

        Order order = optOrder.get();
        if (order.getStatus() != OrderStatus.PENDING) {
            // Only PENDING orders can be cancelled this way. CONFIRMED/FULFILLED orders are
            // not affected by inventory conflict events (stock already decremented at confirmation).
            log.warn("Order is not PENDING (status={}): orderId={}. Discarding inventory event.",
                    order.getStatus(), orderId);
            return;
        }

        // Audit BEFORE mutation (architecture rule)
        auditService.log(new AuditEntry(
                "order_orders", orderId, AuditAction.STATUS_CHANGE,
                "{\"status\":\"" + order.getStatus().name() + "\"}",
                "{\"status\":\"CANCELLED\",\"reason\":\"" + conflictReason + "\"}",
                "system:inventory-event-listener"));

        order.cancel();
        orderRepository.save(order);

        eventPublisher.publish(
                new OrderCancelledEvent(orderId, order.getUserId(), conflictReason, Instant.now()),
                "order.cancelled");

        log.info("Order cancelled by inventory event: orderId={} reason={}", orderId, conflictReason);
    }
}
