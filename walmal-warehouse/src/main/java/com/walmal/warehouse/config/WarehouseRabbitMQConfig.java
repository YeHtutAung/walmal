package com.walmal.warehouse.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for the walmal-warehouse module.
 *
 * <p>IMPORTANT: {@code warehouse.exchange} and {@code order.exchange} are already declared
 * as {@code TopicExchange} beans in {@code walmal-infrastructure}'s {@code RabbitMQTopologyConfig}.
 * This class does NOT redeclare those exchanges to avoid duplicate bean definition errors.</p>
 *
 * <p>This class declares:
 * <ul>
 *   <li>{@code warehouse.order-confirmed.queue} — receives {@code order.confirmed} events</li>
 *   <li>{@code warehouse.order-cancelled.queue} — receives {@code order.cancelled} events</li>
 * </ul>
 * Downstream consumers (Notification module) bind their own queues to {@code warehouse.exchange}
 * using the routing key constants published here.</p>
 */
@Configuration
public class WarehouseRabbitMQConfig {

    // Exchange name constants — actual beans declared in RabbitMQTopologyConfig
    public static final String WAREHOUSE_EXCHANGE = "warehouse.exchange";
    public static final String ORDER_EXCHANGE     = "order.exchange";

    // Routing keys consumed from order.exchange
    public static final String RK_ORDER_CONFIRMED = "order.confirmed";
    public static final String RK_ORDER_CANCELLED = "order.cancelled";

    // Routing keys published by this module (consumed by Notification module)
    public static final String RK_FULFILLMENT_SHIPPED   = "warehouse.fulfillment.shipped";
    public static final String RK_FULFILLMENT_CANCELLED = "warehouse.fulfillment.cancelled";

    // Queue names
    public static final String ORDER_CONFIRMED_QUEUE = "warehouse.order-confirmed.queue";
    public static final String ORDER_CANCELLED_QUEUE = "warehouse.order-cancelled.queue";

    @Bean
    public Queue warehouseOrderConfirmedQueue() {
        return QueueBuilder.durable(ORDER_CONFIRMED_QUEUE).build();
    }

    @Bean
    public Queue warehouseOrderCancelledQueue() {
        return QueueBuilder.durable(ORDER_CANCELLED_QUEUE).build();
    }

    @Bean
    public Binding warehouseOrderConfirmedBinding(Queue warehouseOrderConfirmedQueue) {
        return BindingBuilder
                .bind(warehouseOrderConfirmedQueue)
                .to(new TopicExchange(ORDER_EXCHANGE))
                .with(RK_ORDER_CONFIRMED);
    }

    @Bean
    public Binding warehouseOrderCancelledBinding(Queue warehouseOrderCancelledQueue) {
        return BindingBuilder
                .bind(warehouseOrderCancelledQueue)
                .to(new TopicExchange(ORDER_EXCHANGE))
                .with(RK_ORDER_CANCELLED);
    }
}
