package com.walmal.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationRabbitMQConfig {

    // Routing keys consumed
    public static final String RK_ORDER_CONFIRMED     = "order.confirmed";
    public static final String RK_ORDER_CANCELLED     = "order.cancelled";
    public static final String RK_FULFILLMENT_SHIPPED = "warehouse.fulfillment.shipped";
    public static final String RK_STOCK_LOW           = "inventory.stock.low";
    public static final String RK_USER_REGISTERED     = "auth.user.registered";
    public static final String RK_POS_CONFLICT        = "pos.sync.conflict.resolved";

    // Queue names
    public static final String Q_ORDER_CONFIRMED     = "notification.order-confirmed.queue";
    public static final String Q_ORDER_CANCELLED     = "notification.order-cancelled.queue";
    public static final String Q_FULFILLMENT_SHIPPED = "notification.fulfillment-shipped.queue";
    public static final String Q_STOCK_LOW           = "notification.stock-low.queue";
    public static final String Q_USER_REGISTERED     = "notification.user-registered.queue";
    public static final String Q_POS_CONFLICT        = "notification.pos-conflict.queue";

    // Exchange names — declared by originating modules in RabbitMQTopologyConfig
    static final String ORDER_EXCHANGE     = "order.exchange";
    static final String WAREHOUSE_EXCHANGE = "warehouse.exchange";
    static final String INVENTORY_EXCHANGE = "inventory.exchange";
    static final String AUTH_EXCHANGE      = "auth.exchange";
    static final String POS_EXCHANGE       = "pos.exchange";

    // ── Queues ────────────────────────────────────────────────────────────────

    @Bean Queue notificationOrderConfirmedQueue()    { return new Queue(Q_ORDER_CONFIRMED,     true); }
    @Bean Queue notificationOrderCancelledQueue()    { return new Queue(Q_ORDER_CANCELLED,     true); }
    @Bean Queue notificationFulfillmentShippedQueue(){ return new Queue(Q_FULFILLMENT_SHIPPED, true); }
    @Bean Queue notificationStockLowQueue()          { return new Queue(Q_STOCK_LOW,           true); }
    @Bean Queue notificationUserRegisteredQueue()    { return new Queue(Q_USER_REGISTERED,     true); }
    @Bean Queue notificationPosConflictQueue()       { return new Queue(Q_POS_CONFLICT,        true); }

    // ── Bindings (inline exchange references — actual beans in RabbitMQTopologyConfig) ──

    @Bean
    Binding bindOrderConfirmed(Queue notificationOrderConfirmedQueue) {
        return BindingBuilder.bind(notificationOrderConfirmedQueue)
                .to(new TopicExchange(ORDER_EXCHANGE)).with(RK_ORDER_CONFIRMED);
    }

    @Bean
    Binding bindOrderCancelled(Queue notificationOrderCancelledQueue) {
        return BindingBuilder.bind(notificationOrderCancelledQueue)
                .to(new TopicExchange(ORDER_EXCHANGE)).with(RK_ORDER_CANCELLED);
    }

    @Bean
    Binding bindFulfillmentShipped(Queue notificationFulfillmentShippedQueue) {
        return BindingBuilder.bind(notificationFulfillmentShippedQueue)
                .to(new TopicExchange(WAREHOUSE_EXCHANGE)).with(RK_FULFILLMENT_SHIPPED);
    }

    @Bean
    Binding bindStockLow(Queue notificationStockLowQueue) {
        return BindingBuilder.bind(notificationStockLowQueue)
                .to(new TopicExchange(INVENTORY_EXCHANGE)).with(RK_STOCK_LOW);
    }

    @Bean
    Binding bindUserRegistered(Queue notificationUserRegisteredQueue) {
        return BindingBuilder.bind(notificationUserRegisteredQueue)
                .to(new TopicExchange(AUTH_EXCHANGE)).with(RK_USER_REGISTERED);
    }

    @Bean
    Binding bindPosConflict(Queue notificationPosConflictQueue) {
        return BindingBuilder.bind(notificationPosConflictQueue)
                .to(new TopicExchange(POS_EXCHANGE)).with(RK_POS_CONFLICT);
    }
}
