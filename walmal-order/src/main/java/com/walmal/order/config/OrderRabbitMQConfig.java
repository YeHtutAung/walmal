package com.walmal.order.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for the walmal-order module.
 *
 * <p>IMPORTANT: {@code order.exchange} and {@code inventory.exchange} are ALREADY declared
 * as {@code TopicExchange} beans in {@code walmal-infrastructure}'s {@code RabbitMQTopologyConfig}.
 * This class does NOT redeclare those exchanges to avoid duplicate bean definition errors.</p>
 *
 * <p>This class declares only:
 * <ul>
 *   <li>{@code order.inventory-events.queue} — durable queue that receives
 *       {@code inventory.reservation.released} events from {@code inventory.exchange}.</li>
 *   <li>The binding from {@code inventory.exchange} to this queue.</li>
 * </ul>
 * </p>
 */
@Configuration
public class OrderRabbitMQConfig {

    // Exchange name constants — referenced for queue bindings and event publishing.
    // The actual exchange beans are declared in RabbitMQTopologyConfig (walmal-infrastructure).
    public static final String ORDER_EXCHANGE   = "order.exchange";
    public static final String INVENTORY_EXCHANGE = "inventory.exchange";

    // Routing keys published by this module
    public static final String RK_ORDER_CREATED   = "order.created";
    public static final String RK_ORDER_CONFIRMED = "order.confirmed";
    public static final String RK_ORDER_CANCELLED = "order.cancelled";
    public static final String RK_ORDER_FULFILLED = "order.fulfilled";

    // Routing key consumed from inventory.exchange
    public static final String RK_RESERVATION_RELEASED = "inventory.reservation.released";

    // Queue name
    public static final String INVENTORY_EVENTS_QUEUE = "order.inventory-events.queue";

    /**
     * Durable queue for inventory reservation-released events consumed by this module.
     */
    @Bean
    public Queue orderInventoryEventsQueue() {
        return QueueBuilder.durable(INVENTORY_EVENTS_QUEUE).build();
    }

    /**
     * Binding: inventory.exchange → order.inventory-events.queue for reservation.released events.
     * Uses an Exchange reference (not a bean) to avoid redeclaring the exchange.
     */
    @Bean
    public Binding inventoryReservationReleasedBinding(Queue orderInventoryEventsQueue) {
        return BindingBuilder
                .bind(orderInventoryEventsQueue)
                .to(new TopicExchange(INVENTORY_EXCHANGE))
                .with(RK_RESERVATION_RELEASED);
    }
}
