package com.walmal.notification.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.walmal.notification.application.NotificationEventHandlerService;
import com.walmal.notification.config.NotificationRabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationEventHandlerService handlerService;

    public NotificationEventListener(NotificationEventHandlerService handlerService) {
        this.handlerService = handlerService;
    }

    @RabbitListener(queues = NotificationRabbitMQConfig.Q_ORDER_CONFIRMED)
    public void handleOrderConfirmed(OrderConfirmedMessage message) {
        log.debug("Notification: order confirmed {}", message.orderId());
        handlerService.handleOrderConfirmed(message.orderId(), message.userId());
    }

    @RabbitListener(queues = NotificationRabbitMQConfig.Q_ORDER_CANCELLED)
    public void handleOrderCancelled(OrderCancelledMessage message) {
        log.debug("Notification: order cancelled {}", message.orderId());
        handlerService.handleOrderCancelled(message.orderId(), message.userId(),
                message.cancellationReason());
    }

    @RabbitListener(queues = NotificationRabbitMQConfig.Q_FULFILLMENT_SHIPPED)
    public void handleFulfillmentShipped(FulfillmentShippedMessage message) {
        log.debug("Notification: fulfillment shipped for order {}", message.orderId());
        handlerService.handleFulfillmentShipped(message.orderId(), message.userId(),
                message.carrier(), message.trackingNumber());
    }

    @RabbitListener(queues = NotificationRabbitMQConfig.Q_STOCK_LOW)
    public void handleStockLow(StockLowMessage message) {
        log.debug("Notification: stock low for variant {}", message.variantId());
        handlerService.handleStockLow(message.variantId(), message.locationId(),
                message.availableQuantity(), message.threshold());
    }

    @RabbitListener(queues = NotificationRabbitMQConfig.Q_USER_REGISTERED)
    public void handleUserRegistered(UserRegisteredMessage message) {
        log.debug("Notification: user registered {}", message.userId());
        handlerService.handleUserRegistered(message.userId(), message.username(), message.email());
    }

    @RabbitListener(queues = NotificationRabbitMQConfig.Q_POS_CONFLICT)
    public void handlePosConflict(PosSyncConflictMessage message) {
        log.debug("Notification: POS conflict resolved, cancelled order {}", message.cancelledOrderId());
        handlerService.handlePosSyncConflictResolved(message.cancelledOrderId(), message.userId(),
                message.conflictReason());
    }

    // ── Local message POJOs — no imports from other modules' domain ──────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderConfirmedMessage(UUID orderId, UUID userId, Instant confirmedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderCancelledMessage(UUID orderId, UUID userId, String cancellationReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FulfillmentShippedMessage(UUID orderId, UUID userId, String carrier,
                                     String trackingNumber, Instant shippedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StockLowMessage(UUID variantId, UUID locationId,
                           int availableQuantity, int threshold) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserRegisteredMessage(UUID userId, String username, String email, String role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PosSyncConflictMessage(UUID cancelledOrderId, UUID userId, String conflictReason,
                                  String conflictOutcome, Instant resolvedAt) {}
}
