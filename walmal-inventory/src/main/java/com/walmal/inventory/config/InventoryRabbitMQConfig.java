package com.walmal.inventory.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for the walmal-inventory module.
 *
 * <p>IMPORTANT: {@code inventory.exchange} and {@code product.exchange} are ALREADY declared
 * as {@code TopicExchange} beans in {@code walmal-infrastructure}'s {@code RabbitMQTopologyConfig}.
 * This class does NOT redeclare those exchanges to avoid duplicate bean definition errors.</p>
 *
 * <p>This class declares only:
 * <ul>
 *   <li>The {@code inventory.product-events.queue} — durable queue that receives
 *       product lifecycle events from {@code product.exchange}.</li>
 *   <li>Bindings from {@code product.exchange} to this queue for the routing keys
 *       {@code product.created} and {@code product.deactivated}.</li>
 * </ul>
 * </p>
 */
@Configuration
public class InventoryRabbitMQConfig {

    // Exchange name constants — referenced for queue bindings
    // The actual exchange beans are declared in RabbitMQTopologyConfig (walmal-infrastructure)
    public static final String INVENTORY_EXCHANGE = "inventory.exchange";
    public static final String PRODUCT_EXCHANGE = "product.exchange";

    // Routing keys published by this module
    public static final String RK_RESERVATION_CONFIRMED  = "inventory.reservation.confirmed";
    public static final String RK_RESERVATION_RELEASED   = "inventory.reservation.released";
    public static final String RK_STOCK_LOW              = "inventory.stock.low";
    public static final String RK_STOCK_EXHAUSTED        = "inventory.stock.exhausted";

    // Routing keys consumed from product.exchange
    public static final String RK_PRODUCT_CREATED     = "product.created";
    public static final String RK_PRODUCT_DEACTIVATED = "product.deactivated";

    // Queue name
    public static final String PRODUCT_EVENTS_QUEUE = "inventory.product-events.queue";

    /**
     * Durable queue for product lifecycle events consumed by this module.
     */
    @Bean
    public Queue inventoryProductEventsQueue() {
        return QueueBuilder.durable(PRODUCT_EVENTS_QUEUE).build();
    }

    /**
     * Binding: product.exchange → inventory.product-events.queue for product.created events.
     * Uses an Exchange reference (not a bean) to avoid redeclaring the exchange.
     */
    @Bean
    public Binding productCreatedBinding(Queue inventoryProductEventsQueue) {
        return BindingBuilder
                .bind(inventoryProductEventsQueue)
                .to(new TopicExchange(PRODUCT_EXCHANGE))
                .with(RK_PRODUCT_CREATED);
    }

    /**
     * Binding: product.exchange → inventory.product-events.queue for product.deactivated events.
     */
    @Bean
    public Binding productDeactivatedBinding(Queue inventoryProductEventsQueue) {
        return BindingBuilder
                .bind(inventoryProductEventsQueue)
                .to(new TopicExchange(PRODUCT_EXCHANGE))
                .with(RK_PRODUCT_DEACTIVATED);
    }
}
