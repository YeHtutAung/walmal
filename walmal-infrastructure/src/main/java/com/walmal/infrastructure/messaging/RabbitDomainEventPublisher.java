package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RabbitDomainEventPublisher implements DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitDomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(DomainEvent event) {
        String exchange = deriveExchange(event.getEventType());
        send(exchange, event.getEventType(), event);
    }

    @Override
    public void publish(DomainEvent event, String routingKey) {
        String exchange = deriveExchange(event.getEventType());
        send(exchange, routingKey, event);
    }

    /**
     * Publishes after the surrounding transaction commits, so consumers never
     * observe events for rows that are not yet visible (or that roll back).
     * Outside a transaction the event is sent immediately.
     */
    private void send(String exchange, String routingKey, DomainEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rabbitTemplate.convertAndSend(exchange, routingKey, event);
                }
            });
        } else {
            rabbitTemplate.convertAndSend(exchange, routingKey, event);
        }
    }

    private String deriveExchange(String eventType) {
        String module = eventType.split("\\.")[0];
        return module + ".exchange";
    }
}
