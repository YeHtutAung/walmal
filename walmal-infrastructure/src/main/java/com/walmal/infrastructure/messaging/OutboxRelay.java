package com.walmal.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * Polls {@code outbox_events} and delivers pending domain events to RabbitMQ.
 *
 * <p>Each tick runs in one transaction: lock the oldest PENDING rows
 * ({@code FOR UPDATE SKIP LOCKED} — multi-instance safe), send each as raw
 * JSON, delete on success. A send failure is caught (so the tick still
 * commits its deletes and the attempts increment), the failure is recorded,
 * and the batch halts so later events never overtake earlier ones.</p>
 *
 * <p>After {@link #MAX_ATTEMPTS} failures (~1 minute of continuous broker
 * outage at one attempt per tick) a row is parked as FAILED and skipped
 * thereafter. Operator recovery:
 * {@code UPDATE outbox_events SET status='PENDING', attempts=0 WHERE status='FAILED'}.</p>
 *
 * <p>Messages are sent without a {@code __TypeId__} header: every listener in
 * the system binds to local POJO records via inferred-type Jackson conversion
 * (verified live 2026-07-09).</p>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final int MAX_ATTEMPTS = 60;
    static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relayPendingEvents() {
        for (OutboxEventRow row : outboxRepository.lockPendingBatch(BATCH_SIZE)) {
            try {
                rabbitTemplate.send(row.exchange(), row.routingKey(), toMessage(row));
                outboxRepository.delete(row.id());
            } catch (RuntimeException e) {
                int attempts = row.attempts() + 1;
                boolean exhausted = attempts >= MAX_ATTEMPTS;
                outboxRepository.recordFailure(row.id(), attempts, e.getMessage(), exhausted);
                if (exhausted) {
                    log.error("Outbox event {} ({} -> {}) FAILED after {} attempts: {}",
                            row.id(), row.exchange(), row.routingKey(), attempts, e.getMessage());
                } else {
                    log.warn("Outbox send failed (attempt {}/{}) for event {} ({} -> {}): {}",
                            attempts, MAX_ATTEMPTS, row.id(), row.exchange(), row.routingKey(),
                            e.getMessage());
                }
                return; // halt batch: preserve event ordering
            }
        }
    }

    private Message toMessage(OutboxEventRow row) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        return new Message(row.payload().getBytes(StandardCharsets.UTF_8), props);
    }
}
