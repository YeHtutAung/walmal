package com.walmal.common.event;

import java.time.Instant;
import java.util.UUID;

public abstract class DomainEvent {

    private final UUID eventId;
    private final Instant timestamp;
    private final String eventType;

    protected DomainEvent(String eventType) {
        this.eventId = UUID.randomUUID();
        this.timestamp = Instant.now();
        this.eventType = eventType;
    }

    public UUID getEventId() { return eventId; }
    public Instant getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
}
