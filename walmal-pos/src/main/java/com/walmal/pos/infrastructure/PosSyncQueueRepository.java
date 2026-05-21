package com.walmal.pos.infrastructure;

import com.walmal.pos.domain.PosSyncQueue;
import com.walmal.pos.domain.QueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * JPA repository for {@link PosSyncQueue} — owned exclusively by walmal-pos.
 *
 * <p>IMPORTANT: this repository must NEVER expose a delete operation.
 * Rows in {@code pos_sync_queue} are soft-completed only (PROCESSED or FAILED).
 * Hard deletes are architecture violations in the POS module.</p>
 *
 * Must NEVER be injected into any bean outside the {@code com.walmal.pos} package.
 */
public interface PosSyncQueueRepository extends JpaRepository<PosSyncQueue, UUID> {

    /**
     * Returns all queue rows for a terminal with the given status.
     */
    List<PosSyncQueue> findByTerminalIdAndStatus(UUID terminalId, QueueStatus status);

    /**
     * Counts queue rows for a terminal with the given status.
     * Used by {@code getSyncStatus()} for real-time operator dashboard counts.
     */
    long countByTerminalIdAndStatus(UUID terminalId, QueueStatus status);
}
