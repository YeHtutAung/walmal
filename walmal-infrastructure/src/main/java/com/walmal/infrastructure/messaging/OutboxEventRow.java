package com.walmal.infrastructure.messaging;

import java.util.UUID;

/**
 * One pending row from {@code outbox_events} as selected by
 * {@link OutboxRepository#lockPendingBatch}.
 */
public record OutboxEventRow(
        UUID id,
        String exchange,
        String routingKey,
        String payload,
        int attempts
) {}
