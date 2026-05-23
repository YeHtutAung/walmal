package com.walmal.pos.infrastructure;

import com.walmal.pos.application.dto.PosSyncConflictDto;
import com.walmal.pos.domain.PosSale;
import com.walmal.pos.domain.SyncStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * JPA repository for {@link PosSale} — owned exclusively by walmal-pos.
 * Must NEVER be injected into any bean outside the {@code com.walmal.pos} package.
 */
public interface PosSaleRepository extends JpaRepository<PosSale, UUID> {

    /**
     * Returns a paginated list of sales for a terminal, ordered by the pageable sort.
     */
    Page<PosSale> findByTerminalId(UUID terminalId, Pageable pageable);

    /**
     * Returns offline sale conflict projections (syncStatus != N_A) optionally filtered
     * by terminal and/or sync status. Uses constructor expression to avoid N+1 on terminal.
     */
    @Query("SELECT new com.walmal.pos.application.dto.PosSyncConflictDto(" +
           "s.id, s.terminal.id, s.terminal.name, s.syncStatus, " +
           "s.totalAmount, s.currency, s.soldAt, s.updatedAt) " +
           "FROM PosSale s " +
           "WHERE s.syncStatus <> com.walmal.pos.domain.SyncStatus.N_A " +
           "AND (:terminalId IS NULL OR s.terminal.id = :terminalId) " +
           "AND (:syncStatus IS NULL OR s.syncStatus = :syncStatus)")
    Page<PosSyncConflictDto> findSyncConflicts(
            @Param("terminalId") UUID terminalId,
            @Param("syncStatus") SyncStatus syncStatus,
            Pageable pageable);
}
