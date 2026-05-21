package com.walmal.warehouse.infrastructure.listener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.warehouse.application.WarehouseFulfillmentService;
import com.walmal.warehouse.domain.FulfillmentLine;
import com.walmal.warehouse.domain.FulfillmentOrder;
import com.walmal.warehouse.infrastructure.FulfillmentLineRepository;
import com.walmal.warehouse.infrastructure.FulfillmentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RabbitMQ listener for Order module events consumed by Warehouse.
 *
 * <p>Bound to two queues declared in {@link com.walmal.warehouse.config.WarehouseRabbitMQConfig}:
 * <ul>
 *   <li>{@code warehouse.order-confirmed.queue} — creates fulfillment from order.confirmed</li>
 *   <li>{@code warehouse.order-cancelled.queue} — cancels fulfillment on order.cancelled</li>
 * </ul>
 * </p>
 *
 * <p>IMPORTANT: Uses local message POJOs for deserialization — never imports
 * domain event classes from walmal-order to avoid tight coupling on their internal shape.</p>
 *
 * <p>Idempotency: if a fulfillment already exists for the order (duplicate delivery),
 * the confirmed handler logs and exits without error.</p>
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final WarehouseFulfillmentService fulfillmentService;
    private final FulfillmentOrderRepository fulfillmentRepo;
    private final FulfillmentLineRepository lineRepo;
    private final ObjectMapper objectMapper;

    public OrderEventListener(WarehouseFulfillmentService fulfillmentService,
                               FulfillmentOrderRepository fulfillmentRepo,
                               FulfillmentLineRepository lineRepo,
                               ObjectMapper objectMapper) {
        this.fulfillmentService = fulfillmentService;
        this.fulfillmentRepo = fulfillmentRepo;
        this.lineRepo = lineRepo;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "warehouse.order-confirmed.queue")
    @Transactional
    public void handleOrderConfirmed(String messageBody) {
        OrderConfirmedMessage message;
        try {
            message = objectMapper.readValue(messageBody, OrderConfirmedMessage.class);
        } catch (Exception e) {
            log.error("Failed to deserialize order.confirmed message: {}", messageBody, e);
            return;
        }

        log.debug("Received order.confirmed: orderId={}", message.orderId());

        // Idempotency guard
        if (fulfillmentRepo.findByOrderId(message.orderId()).isPresent()) {
            log.warn("Fulfillment already exists for orderId={}. Ignoring duplicate.", message.orderId());
            return;
        }

        // Serialize shipping address back to JSON for storage
        String shippingAddressJson;
        try {
            shippingAddressJson = objectMapper.writeValueAsString(message.shippingAddress());
        } catch (Exception e) {
            shippingAddressJson = "{}";
        }

        FulfillmentOrder fulfillment = new FulfillmentOrder(
                message.orderId(), message.userId(), shippingAddressJson);
        fulfillmentRepo.save(fulfillment);

        if (message.items() != null) {
            for (OrderConfirmedMessage.ItemSnapshot item : message.items()) {
                FulfillmentLine line = new FulfillmentLine(
                        fulfillment,
                        item.variantId(), item.locationId(),
                        item.skuSnapshot() != null ? item.skuSnapshot() : "",
                        item.quantity());
                lineRepo.save(line);
            }
        }

        log.info("Fulfillment created for orderId={}", message.orderId());
    }

    @RabbitListener(queues = "warehouse.order-cancelled.queue")
    public void handleOrderCancelled(String messageBody) {
        OrderCancelledMessage message;
        try {
            message = objectMapper.readValue(messageBody, OrderCancelledMessage.class);
        } catch (Exception e) {
            log.error("Failed to deserialize order.cancelled message: {}", messageBody, e);
            return;
        }

        log.debug("Received order.cancelled: orderId={}", message.orderId());

        try {
            fulfillmentService.cancelFulfillment(message.orderId());
        } catch (com.walmal.common.exception.BusinessRuleException e) {
            // PACKED/SHIPPED fulfillments are non-cancellable — log and discard
            log.warn("Cannot cancel fulfillment for orderId={}: {}", message.orderId(), e.getMessage());
        }
    }

    // ── Local message POJOs (never import order domain event classes) ─────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderConfirmedMessage(
            UUID orderId,
            UUID userId,
            List<ItemSnapshot> items,
            Object shippingAddress,
            Instant confirmedAt
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ItemSnapshot(UUID variantId, UUID locationId, int quantity, String skuSnapshot) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderCancelledMessage(UUID orderId, UUID userId, String cancellationReason) {}
}
