package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class RabbitDomainEventPublisher implements DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitDomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(DomainEvent event) {
        String exchange = deriveExchange(event.getEventType());
        rabbitTemplate.convertAndSend(exchange, event.getEventType(), event);
    }

    @Override
    public void publish(DomainEvent event, String routingKey) {
        String exchange = deriveExchange(event.getEventType());
        rabbitTemplate.convertAndSend(exchange, routingKey, event);
    }

    private String deriveExchange(String eventType) {
        String module = eventType.split("\\.")[0];
        return module + ".exchange";
    }
}
