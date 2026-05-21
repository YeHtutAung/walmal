package com.walmal.pos.config;

import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ routing key constants for the walmal-pos module.
 *
 * <p>IMPORTANT: {@code pos.exchange} is ALREADY declared as a {@code TopicExchange} bean
 * in {@code walmal-infrastructure}'s {@code RabbitMQTopologyConfig}.
 * This class does NOT redeclare that exchange to avoid duplicate bean definition errors.</p>
 *
 * <p>The POS module publishes only — it does not bind any consumer queues.
 * Downstream modules (Notification, Operator dashboard) bind their own queues to
 * {@code pos.exchange} using these routing keys.</p>
 */
@Configuration
public class PosRabbitMQConfig {

    // Exchange name constant — the actual bean is declared in RabbitMQTopologyConfig
    public static final String POS_EXCHANGE = "pos.exchange";

    // Routing keys published by this module
    public static final String RK_SALE_COMPLETED          = "pos.sale.completed";
    public static final String RK_SALE_SYNCED             = "pos.sale.synced";
    public static final String RK_SYNC_CONFLICT_RESOLVED  = "pos.sync.conflict.resolved";

    // No queue declarations — POS publishes only, no inbound queue.
}
