package com.walmal.pos.domain.event;

import com.walmal.common.event.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when an offline POS sale is processed with NO_CONFLICT outcome.
 * Routing key: {@code pos.sale.synced}
 * Downstream consumers: Notification module.
 */
public class PosSaleSyncedEvent extends DomainEvent {

    /**
     * Immutable snapshot of a single line item included in the event payload.
     */
    public record SaleItemSnapshot(
            UUID variantId,
            int qty,
            String skuSnapshot
    ) {}

    private final UUID terminalId;
    private final UUID saleId;
    private final List<SaleItemSnapshot> items;
    private final Instant syncedAt;

    public PosSaleSyncedEvent(UUID terminalId, UUID saleId,
                               List<SaleItemSnapshot> items, Instant syncedAt) {
        super("pos.sale.synced");
        this.terminalId = terminalId;
        this.saleId = saleId;
        this.items = List.copyOf(items);
        this.syncedAt = syncedAt;
    }

    public UUID getTerminalId() { return terminalId; }
    public UUID getSaleId() { return saleId; }
    public List<SaleItemSnapshot> getItems() { return items; }
    public Instant getSyncedAt() { return syncedAt; }
}
