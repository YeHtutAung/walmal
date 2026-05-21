package com.walmal.pos.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.application.ConflictOutcome;
import com.walmal.pos.application.dto.OfflineSaleLineItem;
import com.walmal.pos.application.dto.OfflineSalePayload;
import com.walmal.pos.application.dto.SyncResultDto;
import com.walmal.pos.application.dto.SyncStatusDto;
import com.walmal.pos.application.impl.PosSyncItemProcessor;
import com.walmal.pos.application.impl.PosSyncItemProcessor.SyncItemResult;
import com.walmal.pos.application.impl.PosSyncServiceImpl;
import com.walmal.pos.domain.PosTerminal;
import com.walmal.pos.domain.PosSyncQueue;
import com.walmal.pos.domain.QueueStatus;
import com.walmal.pos.infrastructure.PosSyncQueueRepository;
import com.walmal.pos.infrastructure.PosTerminalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PosSyncServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PosSyncServiceImplTest {

    @Mock PosSyncQueueRepository posSyncQueueRepository;
    @Mock PosTerminalRepository posTerminalRepository;
    @Mock PosSyncItemProcessor posSyncItemProcessor;

    @InjectMocks PosSyncServiceImpl service;

    private UUID terminalId;
    private UUID locationId;
    private UUID variantId;
    private PosTerminal terminal;

    @BeforeEach
    void setUp() throws Exception {
        // Inject ObjectMapper manually (maxBatchSize uses @Value — set via reflection)
        // JavaTimeModule is required to serialize java.time.Instant in OfflineSalePayload
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        service = new PosSyncServiceImpl(
                posSyncQueueRepository, posTerminalRepository,
                posSyncItemProcessor, mapper);
        // Set the @Value field that Spring would inject at runtime (default 100)
        java.lang.reflect.Field maxBatchField = PosSyncServiceImpl.class.getDeclaredField("maxBatchSize");
        maxBatchField.setAccessible(true);
        maxBatchField.set(service, 100);

        terminalId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        terminal = new PosTerminal("Store A Terminal 1", locationId);

        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.of(terminal));
        when(posSyncQueueRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private OfflineSalePayload buildPayload() {
        OfflineSaleLineItem lineItem = new OfflineSaleLineItem(
                variantId, locationId, 2,
                BigDecimal.valueOf(49.99), "SGD",
                "Test Product", "SKU-001");
        return new OfflineSalePayload(UUID.randomUUID(), List.of(lineItem), "SGD", Instant.now());
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_batchExceedsMaxSize")
    void should_throwBusinessRuleException_when_batchExceedsMaxSize() {
        List<OfflineSalePayload> bigBatch = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            bigBatch.add(buildPayload());
        }

        assertThatThrownBy(() -> service.submitOfflineSync(terminalId, bigBatch))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds maximum of 100");
    }

    @Test
    @DisplayName("should_returnSyncResult_with_correct_counts")
    void should_returnSyncResult_with_correct_counts() {
        OfflineSalePayload payload1 = buildPayload();
        OfflineSalePayload payload2 = buildPayload();

        // Use sequential answers to return different results for each call
        when(posSyncItemProcessor.processItem(any(), any(), any()))
                .thenReturn(new SyncItemResult(payload1.localId(), ConflictOutcome.NO_CONFLICT, true, null))
                .thenReturn(new SyncItemResult(payload2.localId(), ConflictOutcome.POS_PRIORITY, true, null));

        SyncResultDto result = service.submitOfflineSync(terminalId, List.of(payload1, payload2));

        assertThat(result.totalSubmitted()).isEqualTo(2);
        assertThat(result.synced()).isEqualTo(1);
        assertThat(result.conflictResolved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.failures()).isEmpty();
    }

    @Test
    @DisplayName("should_countFailures_when_processItemThrows")
    void should_countFailures_when_processItemThrows() {
        OfflineSalePayload payload = buildPayload();

        when(posSyncItemProcessor.processItem(any(), eq(payload), any()))
                .thenThrow(new RuntimeException("Inventory error"));

        SyncResultDto result = service.submitOfflineSync(terminalId, List.of(payload));

        assertThat(result.totalSubmitted()).isEqualTo(1);
        assertThat(result.synced()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).localId()).isEqualTo(payload.localId());
        verify(posSyncItemProcessor).markQueueFailed(any(), any());
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_terminalNotFound_in_getSyncStatus")
    void should_throwResourceNotFoundException_when_terminalNotFound_in_getSyncStatus() {
        UUID unknownId = UUID.randomUUID();
        when(posTerminalRepository.existsById(unknownId)).thenReturn(false);

        assertThatThrownBy(() -> service.getSyncStatus(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("should_returnSyncStatus_with_counts")
    void should_returnSyncStatus_with_counts() {
        when(posTerminalRepository.existsById(terminalId)).thenReturn(true);
        when(posSyncQueueRepository.countByTerminalIdAndStatus(terminalId, QueueStatus.PENDING))
                .thenReturn(3L);
        when(posSyncQueueRepository.countByTerminalIdAndStatus(terminalId, QueueStatus.FAILED))
                .thenReturn(1L);

        SyncStatusDto status = service.getSyncStatus(terminalId);

        assertThat(status.terminalId()).isEqualTo(terminalId);
        assertThat(status.pendingCount()).isEqualTo(3L);
        assertThat(status.failedCount()).isEqualTo(1L);
    }
}
