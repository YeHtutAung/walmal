package com.walmal.infrastructure.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQTopologyConfig {

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange("auth.exchange");
    }

    @Bean
    public TopicExchange productExchange() {
        return new TopicExchange("product.exchange");
    }

    @Bean
    public TopicExchange inventoryExchange() {
        return new TopicExchange("inventory.exchange");
    }

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange("order.exchange");
    }

    @Bean
    public TopicExchange posExchange() {
        return new TopicExchange("pos.exchange");
    }

    @Bean
    public TopicExchange warehouseExchange() {
        return new TopicExchange("warehouse.exchange");
    }

    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange("notification.exchange");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
