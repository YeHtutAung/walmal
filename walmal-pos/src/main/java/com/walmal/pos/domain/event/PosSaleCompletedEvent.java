package com.walmal.pos.domain.event;

import com.walmal.common.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when an online POS sale is committed.
 * Routing key: {@code pos.sale.completed}
 * Downstream consumers: Notification module (operator/customer alerts).
 */
public class PosSaleCompletedEvent extends DomainEvent {

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
    private final UUID orderId;
    private final UUID cashierId;
    private final List<SaleItemSnapshot> items;
    private final BigDecimal totalAmount;
    private final String currency;
    private final Instant completedAt;

    public PosSaleCompletedEvent(UUID terminalId, UUID saleId, UUID orderId, UUID cashierId,
                                  List<SaleItemSnapshot> items, BigDecimal totalAmount,
                                  String currency, Instant completedAt) {
        super("pos.sale.completed");
        this.terminalId = terminalId;
        this.saleId = saleId;
        this.orderId = orderId;
        this.cashierId = cashierId;
        this.items = List.copyOf(items);
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.completedAt = completedAt;
    }

    public UUID getTerminalId() { return terminalId; }
    public UUID getSaleId() { return saleId; }
    public UUID getOrderId() { return orderId; }
    public UUID getCashierId() { return cashierId; }
    public List<SaleItemSnapshot> getItems() { return items; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public Instant getCompletedAt() { return completedAt; }
}
