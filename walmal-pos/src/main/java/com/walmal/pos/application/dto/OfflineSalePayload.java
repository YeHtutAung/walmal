package com.walmal.pos.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single offline sale payload submitted by a POS terminal during sync.
 *
 * <p>{@code localId} is the device-generated UUID used for idempotency.
 * It is stored in {@code pos_sync_queue.sale_data} JSONB for reference.
 * If the terminal retries the sync batch, the server can detect duplicates
 * by checking whether a PROCESSED queue row already exists for this localId.</p>
 *
 * @param localId   device-generated UUID for idempotency reference
 * @param items     line items sold in this transaction
 * @param currency  ISO-4217 currency code for the sale
 * @param soldAt    device clock timestamp when the sale was recorded offline
 */
public record OfflineSalePayload(
        UUID localId,
        List<OfflineSaleLineItem> items,
        String currency,
        Instant soldAt
) {}
