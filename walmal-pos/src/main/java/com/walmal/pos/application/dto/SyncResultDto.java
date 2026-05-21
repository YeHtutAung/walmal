package com.walmal.pos.application.dto;

import java.util.List;

/**
 * Summary result returned after processing an offline sync batch.
 *
 * <p>Allows partial success: each item in the batch is processed independently.
 * Failures do not roll back successfully processed items (per ADR-6).</p>
 *
 * @param totalSubmitted   number of payloads in the submitted batch
 * @param synced           number successfully synced (NO_CONFLICT outcome)
 * @param conflictResolved number synced with a conflict resolved (POS_PRIORITY or BUFFER_EXHAUSTED)
 * @param failed           number that permanently failed
 * @param failures         detail records for each failed item
 */
public record SyncResultDto(
        int totalSubmitted,
        int synced,
        int conflictResolved,
        int failed,
        List<SyncFailureDetail> failures
) {}
