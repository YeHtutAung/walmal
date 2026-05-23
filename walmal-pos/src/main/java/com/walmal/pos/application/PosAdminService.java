package com.walmal.pos.application;

import com.walmal.pos.application.dto.PosSyncConflictDto;
import com.walmal.pos.application.dto.PosTerminalDto;
import com.walmal.pos.domain.SyncStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Admin-only service interface for POS monitoring and oversight.
 *
 * <p>ISP: admin read concerns (full terminal list, conflict log) are segregated
 * from the POS operational interfaces (PosTerminalService, PosSaleService, PosSyncService)
 * which are consumed by the terminal devices themselves.</p>
 */
public interface PosAdminService {

    /**
     * Returns a paginated list of all POS terminals.
     */
    Page<PosTerminalDto> listAllTerminals(Pageable pageable);

    /**
     * Returns offline POS sales filtered by their sync outcome.
     * When {@code terminalId} or {@code syncStatus} are null, those filters are omitted.
     *
     * @param terminalId optional terminal UUID filter
     * @param syncStatus optional sync status filter; null returns all non-N_A sales
     */
    Page<PosSyncConflictDto> listSyncConflicts(
            @Nullable UUID terminalId,
            @Nullable SyncStatus syncStatus,
            Pageable pageable);
}
