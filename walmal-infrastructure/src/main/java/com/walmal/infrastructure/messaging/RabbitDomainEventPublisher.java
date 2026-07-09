package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RabbitDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitDomainEventPublisher.class);

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
                    try {
                        rabbitTemplate.convertAndSend(exchange, routingKey, event);
                    } catch (RuntimeException e) {
                        // The business transaction is already committed; a broker
                        // failure here must not fail the caller. The event is lost
                        // (at-most-once) — log for operator recovery.
                        log.error("Failed to publish {} to {} after commit; event lost",
                                routingKey, exchange, e);
                    }
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
