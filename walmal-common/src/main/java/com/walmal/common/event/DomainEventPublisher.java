package com.walmal.common.event;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
    void publish(DomainEvent event, String routingKey);
}
