package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Transactional-outbox implementation of {@link DomainEventPublisher}.
 *
 * <p>Publishing writes a row to {@code outbox_events} in the caller's
 * transaction (see {@link OutboxRepository#insert}); {@code OutboxRelay}
 * delivers it to RabbitMQ asynchronously (at-least-once, ~1 s latency).
 * If the business transaction rolls back, the row rolls back with it —
 * consumers never see events for uncommitted state.</p>
 *
 * <p>The event is serialized here with the same {@code jsonMessageConverter}
 * bean the RabbitTemplate previously used, so payloads on the wire are
 * byte-identical to the pre-outbox format.</p>
 *
 * <p>A Jackson serialization failure propagates and fails the business
 * transaction: an unserializable event is a programming error, and committing
 * business state whose consumers can never be notified would be worse.</p>
 */
@Service
public class RabbitDomainEventPublisher implements DomainEventPublisher {

    private final OutboxRepository outboxRepository;
    private final MessageConverter messageConverter;

    public RabbitDomainEventPublisher(OutboxRepository outboxRepository,
                                      MessageConverter messageConverter) {
        this.outboxRepository = outboxRepository;
        this.messageConverter = messageConverter;
    }

    @Override
    public void publish(DomainEvent event) {
        publishInternal(event, event.getEventType());
    }

    @Override
    public void publish(DomainEvent event, String routingKey) {
        publishInternal(event, routingKey);
    }

    private void publishInternal(DomainEvent event, String routingKey) {
        String exchange = deriveExchange(event.getEventType());
        Message message = messageConverter.toMessage(event, new MessageProperties());
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        outboxRepository.insert(UUID.randomUUID(), exchange, routingKey, payload);
    }

    private String deriveExchange(String eventType) {
        String module = eventType.split("\\.")[0];
        return module + ".exchange";
    }
}
