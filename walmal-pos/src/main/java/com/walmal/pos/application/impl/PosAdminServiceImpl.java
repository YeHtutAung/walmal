package com.walmal.pos.application.impl;

import com.walmal.pos.application.PosAdminService;
import com.walmal.pos.application.dto.PosSyncConflictDto;
import com.walmal.pos.application.dto.PosTerminalDto;
import com.walmal.pos.domain.SyncStatus;
import com.walmal.pos.infrastructure.PosSaleRepository;
import com.walmal.pos.infrastructure.PosTerminalRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PosAdminServiceImpl implements PosAdminService {

    private final PosTerminalRepository terminalRepository;
    private final PosSaleRepository saleRepository;

    public PosAdminServiceImpl(PosTerminalRepository terminalRepository,
                                PosSaleRepository saleRepository) {
        this.terminalRepository = terminalRepository;
        this.saleRepository = saleRepository;
    }

    @Override
    public Page<PosTerminalDto> listAllTerminals(Pageable pageable) {
        return terminalRepository.findAll(pageable)
                .map(t -> new PosTerminalDto(
                        t.getId(), t.getName(), t.getLocationId(),
                        t.getStatus(), t.getLastSeenAt()));
    }

    @Override
    public Page<PosSyncConflictDto> listSyncConflicts(
            @Nullable UUID terminalId,
            @Nullable SyncStatus syncStatus,
            Pageable pageable) {
        return saleRepository.findSyncConflicts(terminalId, syncStatus, pageable);
    }
}
