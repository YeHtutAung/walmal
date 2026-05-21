package com.walmal.pos.application.dto;

import com.walmal.pos.domain.SaleMode;
import com.walmal.pos.domain.SyncStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full read projection of a POS sale including all line items.
 *
 * @param id             sale UUID
 * @param terminalId     the terminal that recorded the sale
 * @param onlineOrderId  cross-module ref to the order created by OrderCreationService; null for offline sales
 * @param soldAt         timestamp of the sale (server clock for online; device clock for offline)
 * @param totalAmount    total sale amount
 * @param currency       ISO-4217 currency code
 * @param saleMode       ONLINE or OFFLINE
 * @param syncStatus     N_A for online sales; lifecycle status for offline sales
 * @param items          line items
 */
public record PosSaleDto(
        UUID id,
        UUID terminalId,
        UUID onlineOrderId,
        Instant soldAt,
        BigDecimal totalAmount,
        String currency,
        SaleMode saleMode,
        SyncStatus syncStatus,
        List<PosSaleItemDto> items
) {}
