package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RabbitDomainEventPublisherTest {

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private final Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
    private RabbitDomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RabbitDomainEventPublisher(outboxRepository, converter);
    }

    @Test
    void should_insertOutboxRow_when_eventPublished() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event);

        verify(outboxRepository).insert(
                any(UUID.class), eq("order.exchange"), eq("order.created"), any(String.class));
    }

    @Test
    void should_useCustomRoutingKey_when_provided() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event, "order.created.priority");

        verify(outboxRepository).insert(
                any(UUID.class), eq("order.exchange"), eq("order.created.priority"), any(String.class));
    }

    @Test
    void should_serializePayloadIdenticallyToRabbitMessageConverter() {
        DomainEvent event = new DomainEvent("order.confirmed") {};
        // What a consumer would receive today if RabbitTemplate serialized the event:
        String expectedJson = new String(
                converter.toMessage(event, new MessageProperties()).getBody(),
                StandardCharsets.UTF_8);

        publisher.publish(event);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).insert(any(UUID.class), any(), any(), payload.capture());
        // Byte-identical serialization, including timestamp format (spec requirement)
        assertThat(payload.getValue()).isEqualTo(expectedJson);
        assertThat(payload.getValue()).contains("\"eventType\":\"order.confirmed\"");
        assertThat(payload.getValue()).contains("\"timestamp\":");
    }
}
