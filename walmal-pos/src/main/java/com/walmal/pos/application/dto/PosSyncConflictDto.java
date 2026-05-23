package com.walmal.pos.application.dto;

import com.walmal.pos.domain.SyncStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projection of an offline POS sale that went through the sync pipeline.
 * Used by the admin sync-conflict monitoring page to surface FAILED and
 * CONFLICT_RESOLVED outcomes that require operator review.
 *
 * @param id           pos_sales.id (shown as the conflict entry ID)
 * @param terminalId   the terminal that submitted the offline sale
 * @param terminalName display name of the terminal
 * @param syncStatus   current sync lifecycle outcome
 * @param totalAmount  total sale amount for context
 * @param currency     ISO-4217 currency code
 * @param soldAt       device-clock timestamp of the original sale
 * @param updatedAt    last time the sync status was mutated
 */
public record PosSyncConflictDto(
        UUID id,
        UUID terminalId,
        String terminalName,
        SyncStatus syncStatus,
        BigDecimal totalAmount,
        String currency,
        Instant soldAt,
        Instant updatedAt
) {}
