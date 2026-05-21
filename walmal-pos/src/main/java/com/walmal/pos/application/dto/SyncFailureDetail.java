package com.walmal.pos.application.dto;

import java.util.UUID;

/**
 * Detail of a single failed offline sale within a sync batch result.
 *
 * @param localId  the device-generated UUID from the original {@link OfflineSalePayload}
 * @param reason   human-readable failure reason for operator investigation
 */
public record SyncFailureDetail(
        UUID localId,
        String reason
) {}
