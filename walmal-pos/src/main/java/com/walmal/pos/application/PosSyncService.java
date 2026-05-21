package com.walmal.pos.application;

import com.walmal.pos.application.dto.OfflineSalePayload;
import com.walmal.pos.application.dto.SyncResultDto;
import com.walmal.pos.application.dto.SyncStatusDto;

import java.util.List;
import java.util.UUID;

/**
 * Public service interface for offline POS sync.
 *
 * <p>ISP: sync is a separate concern from sale creation and terminal management.
 * No other module injects this interface. Only {@code PosSyncController} consumes it.</p>
 *
 * <p>Architecture rule: the outer {@code submitOfflineSync} method is NOT {@code @Transactional}.
 * Per-item transactions are managed by {@code PosSyncItemProcessor} with REQUIRES_NEW propagation.</p>
 */
public interface PosSyncService {

    /**
     * Processes a batch of offline sale payloads submitted by a POS terminal after reconnection.
     *
     * <p>Each payload is processed independently in its own REQUIRES_NEW transaction:
     * a failure on one item does not roll back successfully processed items.
     * Maximum batch size is 100 items (configurable via {@code pos.sync.max-batch-size}).</p>
     *
     * @param terminalId   the submitting terminal UUID
     * @param offlineSales list of offline sale payloads (max 100)
     * @return summary with counts of synced, conflict-resolved, and failed items
     * @throws com.walmal.common.exception.BusinessRuleException if the batch exceeds max size
     * @throws com.walmal.common.exception.ResourceNotFoundException if the terminal does not exist
     */
    SyncResultDto submitOfflineSync(UUID terminalId, List<OfflineSalePayload> offlineSales);

    /**
     * Returns real-time sync health for a terminal (pending and failed queue counts).
     *
     * <p>Not cached — live operational status for dashboards.</p>
     *
     * @param terminalId the terminal UUID
     * @return sync status summary
     */
    SyncStatusDto getSyncStatus(UUID terminalId);
}
