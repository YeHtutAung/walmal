package com.walmal.auth.config;

import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for the auth module.
 *
 * <p>The {@code auth.exchange} TopicExchange bean is already declared in
 * {@code walmal-infrastructure}'s {@code RabbitMQTopologyConfig}. Redeclaring it
 * here would cause a duplicate bean definition conflict.</p>
 *
 * <p>Queues and bindings for the auth module's events (auth.user.registered,
 * auth.user.deactivated) are declared by the consuming modules (notification,
 * order) — not the publishing module. Publishers only need the exchange name,
 * which is passed as a routing key string in {@code DomainEventPublisher.publish()}.</p>
 *
 * <p>This class is intentionally empty and kept as a placeholder for any future
 * auth-module-specific AMQP configuration (dead-letter queues, bindings, etc.).</p>
 */
@Configuration
public class AuthRabbitMQConfig {
    // Intentionally empty — see Javadoc above.
}
