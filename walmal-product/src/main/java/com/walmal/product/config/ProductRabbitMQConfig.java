package com.walmal.product.config;

import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for the walmal-product module.
 *
 * <p>The {@code product.exchange} TopicExchange bean is already declared in
 * {@code walmal-infrastructure}'s {@code RabbitMQTopologyConfig}. Redeclaring it
 * here would cause a duplicate bean definition conflict at runtime.</p>
 *
 * <p>Routing keys used by this module (declared as constants for reference):
 * <ul>
 *   <li>{@code product.created} — new variant activated, published to inventory</li>
 *   <li>{@code product.price.changed} — price updated, published to order and POS</li>
 *   <li>{@code product.details.changed} — product metadata updated (no MVP consumer)</li>
 *   <li>{@code product.deactivated} — product or variant set INACTIVE</li>
 * </ul>
 * </p>
 *
 * <p>Queues and bindings for these events are declared by the consuming modules
 * (inventory, order, notification) — not here. Publishers only need the exchange name,
 * which is passed as a routing key string in {@code DomainEventPublisher.publish()}.</p>
 *
 * <p>This class is intentionally empty and kept as a placeholder for any future
 * product-module-specific AMQP configuration (dead-letter queues, consumer bindings).</p>
 */
@Configuration
public class ProductRabbitMQConfig {

    /** Exchange name — declared in RabbitMQTopologyConfig, referenced here for documentation. */
    public static final String EXCHANGE = "product.exchange";

    /** Routing keys published by this module. */
    public static final String RK_PRODUCT_CREATED = "product.created";
    public static final String RK_PRICE_CHANGED = "product.price.changed";
    public static final String RK_DETAILS_CHANGED = "product.details.changed";
    public static final String RK_PRODUCT_DEACTIVATED = "product.deactivated";

    // Intentionally empty — see Javadoc above.
}
