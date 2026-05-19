package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitDomainEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private RabbitDomainEventPublisher publisher;

    @Test
    void should_sendToRabbitMQ_when_eventPublished() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event);

        verify(rabbitTemplate).convertAndSend(
            eq("order.exchange"),
            eq("order.created"),
            eq(event)
        );
    }

    @Test
    void should_useCustomRoutingKey_when_provided() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event, "order.created.priority");

        verify(rabbitTemplate).convertAndSend(
            eq("order.exchange"),
            eq("order.created.priority"),
            eq(event)
        );
    }
}
