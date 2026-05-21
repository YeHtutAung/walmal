package com.walmal.pos.domain.event;

import com.walmal.common.event.DomainEvent;
import com.walmal.inventory.application.ConflictOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an offline POS sale is processed with a conflict outcome
 * (POS_PRIORITY or BUFFER_EXHAUSTED).
 * Routing key: {@code pos.sync.conflict.resolved}
 * Downstream consumers: Notification module, operator dashboard.
 */
public class PosSyncConflictResolvedEvent extends DomainEvent {

    private final UUID terminalId;
    private final UUID saleId;
    private final UUID cancelledOrderId;   // nullable — null if no web order was cancelled
    private final String conflictReason;
    private final ConflictOutcome conflictOutcome;
    private final Instant resolvedAt;

    public PosSyncConflictResolvedEvent(UUID terminalId, UUID saleId,
                                         UUID cancelledOrderId, String conflictReason,
                                         ConflictOutcome conflictOutcome, Instant resolvedAt) {
        super("pos.sync.conflict.resolved");
        this.terminalId = terminalId;
        this.saleId = saleId;
        this.cancelledOrderId = cancelledOrderId;
        this.conflictReason = conflictReason;
        this.conflictOutcome = conflictOutcome;
        this.resolvedAt = resolvedAt;
    }

    public UUID getTerminalId() { return terminalId; }
    public UUID getSaleId() { return saleId; }
    public UUID getCancelledOrderId() { return cancelledOrderId; }
    public String getConflictReason() { return conflictReason; }
    public ConflictOutcome getConflictOutcome() { return conflictOutcome; }
    public Instant getResolvedAt() { return resolvedAt; }
}
