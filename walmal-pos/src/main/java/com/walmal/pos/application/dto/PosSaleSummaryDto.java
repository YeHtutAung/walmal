package com.walmal.pos.application.dto;

import com.walmal.pos.domain.SaleMode;
import com.walmal.pos.domain.SyncStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight read projection of a POS sale for paginated list views.
 * Does not include line items — use {@link PosSaleDto} for the full detail view.
 *
 * @param id          sale UUID
 * @param soldAt      timestamp of the sale
 * @param totalAmount total sale amount
 * @param saleMode    ONLINE or OFFLINE
 * @param syncStatus  sync lifecycle status
 */
public record PosSaleSummaryDto(
        UUID id,
        Instant soldAt,
        BigDecimal totalAmount,
        SaleMode saleMode,
        SyncStatus syncStatus
) {}
