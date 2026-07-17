package com.walmal.pos.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.application.ConflictOutcome;
import com.walmal.pos.application.PosSyncService;
import com.walmal.pos.application.dto.OfflineSalePayload;
import com.walmal.pos.application.dto.SyncFailureDetail;
import com.walmal.pos.application.dto.SyncResultDto;
import com.walmal.pos.application.dto.SyncStatusDto;
import com.walmal.pos.application.impl.PosSyncItemProcessor.SyncItemResult;
import com.walmal.pos.domain.PosTerminal;
import com.walmal.pos.domain.PosSyncQueue;
import com.walmal.pos.domain.QueueStatus;
import com.walmal.pos.infrastructure.PosSyncQueueRepository;
import com.walmal.pos.infrastructure.PosTerminalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of {@link PosSyncService}.
 *
 * <p>This method is intentionally NOT {@code @Transactional} at the outer level.
 * Per-item transactions are delegated to {@link PosSyncItemProcessor} with
 * {@code REQUIRES_NEW} propagation, ensuring failures on one item do not
 * roll back successfully processed items (ADR-6 Flag 2).</p>
 *
 * <p>Two-phase per-item protocol:
 * <ol>
 *   <li>Persist Phase 1 queue row (always commits, gives operator visibility).</li>
 *   <li>Call {@link PosSyncItemProcessor#processItem} (REQUIRES_NEW — can roll back independently).</li>
 *   <li>On exception: call {@link PosSyncItemProcessor#markQueueFailed} (REQUIRES_NEW).</li>
 * </ol>
 * </p>
 */
@Service
public class PosSyncServiceImpl implements PosSyncService {

    private static final Logger log = LoggerFactory.getLogger(PosSyncServiceImpl.class);

    @Value("${pos.sync.max-batch-size:100}")
    private int maxBatchSize;

    private final PosSyncQueueRepository posSyncQueueRepository;
    private final PosTerminalRepository posTerminalRepository;
    private final PosSyncItemProcessor posSyncItemProcessor;
    private final ObjectMapper objectMapper;

    public PosSyncServiceImpl(
            PosSyncQueueRepository posSyncQueueRepository,
            PosTerminalRepository posTerminalRepository,
            PosSyncItemProcessor posSyncItemProcessor,
            ObjectMapper objectMapper) {
        this.posSyncQueueRepository = posSyncQueueRepository;
        this.posTerminalRepository = posTerminalRepository;
        this.posSyncItemProcessor = posSyncItemProcessor;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes a batch of offline sale payloads.
     * NOT {@code @Transactional} — per-item transactions managed by PosSyncItemProcessor.
     */
    @Override
    public SyncResultDto submitOfflineSync(UUID terminalId, List<OfflineSalePayload> offlineSales) {

        if (offlineSales.size() > maxBatchSize) {
            throw new BusinessRuleException(
                    "Sync batch size " + offlineSales.size() +
                    " exceeds maximum of " + maxBatchSize + " items");
        }

        PosTerminal terminal = posTerminalRepository.findById(terminalId)
                .orElseThrow(() -> new ResourceNotFoundException("PosTerminal", terminalId));

        int synced = 0;
        int conflictResolved = 0;
        int failed = 0;
        List<SyncFailureDetail> failures = new ArrayList<>();

        int duplicates = 0;

        for (OfflineSalePayload payload : offlineSales) {

            // Idempotency guard (ADR-6): if this terminal already PROCESSED a
            // payload with this localId, the batch is a resubmission. Skip it —
            // do NOT re-queue or reprocess (that would double-decrement stock and
            // re-resolve the conflict). A duplicate is already-synced, so it
            // counts toward `synced` (idempotent success), not `failed`.
            if (payload.localId() != null
                    && posSyncQueueRepository.existsByTerminalIdAndLocalIdAndStatus(
                            terminalId, payload.localId(), QueueStatus.PROCESSED)) {
                log.info("Skipping duplicate offline sale localId={} for terminal={} (already PROCESSED)",
                        payload.localId(), terminalId);
                duplicates++;
                synced++;
                continue;
            }

            // Phase 1: persist queue row — this always commits (operator visibility)
            PosSyncQueue queueRow;
            try {
                String saleDataJson = objectMapper.writeValueAsString(payload);
                queueRow = new PosSyncQueue(terminal, saleDataJson, payload.localId());
                queueRow = posSyncQueueRepository.save(queueRow);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize offline payload localId={}: {}",
                        payload.localId(), e.getMessage());
                failed++;
                failures.add(new SyncFailureDetail(payload.localId(),
                        "Payload serialization failed: " + e.getMessage()));
                continue;
            }

            // Phase 2: process the item in its own REQUIRES_NEW transaction
            try {
                SyncItemResult result = posSyncItemProcessor.processItem(terminal, payload, queueRow);

                if (result.outcome() == ConflictOutcome.POS_PRIORITY
                        || result.outcome() == ConflictOutcome.BUFFER_EXHAUSTED) {
                    conflictResolved++;
                } else {
                    synced++;
                }

            } catch (Exception e) {
                log.error("Failed to process offline sale localId={} for terminal={}: {}",
                        payload.localId(), terminalId, e.getMessage());

                // Catch-path: mark the Phase 1 queue row as FAILED in a new REQUIRES_NEW tx
                String failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                try {
                    posSyncItemProcessor.markQueueFailed(queueRow, failureReason);
                } catch (Exception markFailedEx) {
                    log.error("Failed to mark queue row as FAILED for queueId={}: {}",
                            queueRow.getId(), markFailedEx.getMessage());
                }

                failed++;
                failures.add(new SyncFailureDetail(payload.localId(), failureReason));
            }
        }

        log.info("Offline sync complete: terminalId={} submitted={} synced={} (of which duplicates={}) conflictResolved={} failed={}",
                terminalId, offlineSales.size(), synced, duplicates, conflictResolved, failed);

        return new SyncResultDto(offlineSales.size(), synced, conflictResolved, failed, failures);
    }

    @Override
    public SyncStatusDto getSyncStatus(UUID terminalId) {
        // Validate terminal exists
        if (!posTerminalRepository.existsById(terminalId)) {
            throw new ResourceNotFoundException("PosTerminal", terminalId);
        }

        long pendingCount = posSyncQueueRepository.countByTerminalIdAndStatus(
                terminalId, QueueStatus.PENDING);
        long failedCount = posSyncQueueRepository.countByTerminalIdAndStatus(
                terminalId, QueueStatus.FAILED);

        return new SyncStatusDto(terminalId, pendingCount, failedCount);
    }
}
