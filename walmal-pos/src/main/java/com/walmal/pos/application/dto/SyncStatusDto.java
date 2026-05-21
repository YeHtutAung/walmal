package com.walmal.pos.application.dto;

import java.util.UUID;

/**
 * Real-time sync health summary for a POS terminal.
 * Used by the operator dashboard to surface terminals with pending or failed sync items.
 *
 * @param terminalId   the terminal UUID
 * @param pendingCount number of queue rows still in PENDING state
 * @param failedCount  number of queue rows in FAILED state requiring operator attention
 */
public record SyncStatusDto(
        UUID terminalId,
        long pendingCount,
        long failedCount
) {}
