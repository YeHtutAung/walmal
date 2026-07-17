package com.walmal.pos.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.inventory.application.ConflictOutcome;
import com.walmal.inventory.application.ConflictResolutionResult;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.pos.application.dto.OfflineSaleLineItem;
import com.walmal.pos.application.dto.OfflineSalePayload;
import com.walmal.pos.application.impl.PosSyncItemProcessor;
import com.walmal.pos.application.impl.PosSyncItemProcessor.SyncItemResult;
import com.walmal.pos.domain.*;
import com.walmal.pos.domain.event.PosSaleSyncedEvent;
import com.walmal.pos.domain.event.PosSyncConflictResolvedEvent;
import com.walmal.pos.infrastructure.PosSaleItemRepository;
import com.walmal.pos.infrastructure.PosSaleRepository;
import com.walmal.pos.infrastructure.PosSyncQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PosSyncItemProcessor}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PosSyncItemProcessorTest {

    @Mock PosSaleRepository posSaleRepository;
    @Mock PosSaleItemRepository posSaleItemRepository;
    @Mock PosSyncQueueRepository posSyncQueueRepository;
    @Mock InventoryReservationService inventoryReservationService;
    @Mock AuditService auditService;
    @Mock DomainEventPublisher eventPublisher;

    @InjectMocks PosSyncItemProcessor processor;

    private PosTerminal terminal;
    private UUID terminalId;
    private UUID variantId;
    private UUID locationId;
    private OfflineSalePayload payload;
    private PosSyncQueue queueRow;

    @BeforeEach
    void setUp() throws Exception {
        // ObjectMapper is injected via @InjectMocks — need to provide it manually
        processor = new PosSyncItemProcessor(
                posSaleRepository, posSaleItemRepository, posSyncQueueRepository,
                inventoryReservationService, auditService, eventPublisher, new ObjectMapper());
        // @Value field Spring would inject at runtime (default 7 offline days)
        java.lang.reflect.Field ageField = PosSyncItemProcessor.class.getDeclaredField("maxOfflineAgeDays");
        ageField.setAccessible(true);
        ageField.set(processor, 7);

        terminalId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        terminal = new PosTerminal("Store A Terminal 1", locationId);

        OfflineSaleLineItem lineItem = new OfflineSaleLineItem(
                variantId, locationId, 2,
                BigDecimal.valueOf(49.99), "SGD",
                "Test Product", "SKU-001");
        payload = new OfflineSalePayload(UUID.randomUUID(), List.of(lineItem), "SGD", Instant.now());

        queueRow = new PosSyncQueue(terminal, "{}", payload.localId());

        when(posSaleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posSaleItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posSyncQueueRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("should_syncSuccessfully_when_noConflict")
    void should_syncSuccessfully_when_noConflict() {
        when(inventoryReservationService.resolveConflict(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(ConflictResolutionResult.noConflict(locationId));

        SyncItemResult result = processor.processItem(terminal, payload, queueRow);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo(ConflictOutcome.NO_CONFLICT);
    }

    @Test
    @DisplayName("should_markConflictResolved_when_posPriority")
    void should_markConflictResolved_when_posPriority() {
        UUID cancelledOrderId = UUID.randomUUID();
        when(inventoryReservationService.resolveConflict(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(ConflictResolutionResult.posPriority(locationId, cancelledOrderId));

        SyncItemResult result = processor.processItem(terminal, payload, queueRow);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo(ConflictOutcome.POS_PRIORITY);
    }

    @Test
    @DisplayName("should_markConflictResolved_when_bufferExhausted")
    void should_markConflictResolved_when_bufferExhausted() {
        UUID cancelledOrderId = UUID.randomUUID();
        when(inventoryReservationService.resolveConflict(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(ConflictResolutionResult.bufferExhausted(cancelledOrderId));

        SyncItemResult result = processor.processItem(terminal, payload, queueRow);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo(ConflictOutcome.BUFFER_EXHAUSTED);
    }

    @Test
    @DisplayName("should_markFailed_when_resolveConflictThrows")
    void should_markFailed_when_resolveConflictThrows() {
        when(inventoryReservationService.resolveConflict(any(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("Inventory service unavailable"));

        assertThatThrownBy(() -> processor.processItem(terminal, payload, queueRow))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Inventory service unavailable");
    }

    @Test
    @DisplayName("should_publishSyncedEvent_when_noConflict")
    void should_publishSyncedEvent_when_noConflict() {
        when(inventoryReservationService.resolveConflict(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(ConflictResolutionResult.noConflict(locationId));

        processor.processItem(terminal, payload, queueRow);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture(), eq("pos.sale.synced"));
        assertThat(eventCaptor.getValue()).isInstanceOf(PosSaleSyncedEvent.class);
    }

    @Test
    @DisplayName("should_publishConflictResolvedEvent_when_conflict")
    void should_publishConflictResolvedEvent_when_conflict() {
        UUID cancelledOrderId = UUID.randomUUID();
        when(inventoryReservationService.resolveConflict(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(ConflictResolutionResult.posPriority(locationId, cancelledOrderId));

        processor.processItem(terminal, payload, queueRow);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture(), eq("pos.sync.conflict.resolved"));
        assertThat(eventCaptor.getValue()).isInstanceOf(PosSyncConflictResolvedEvent.class);
    }

    // ── #7: device-supplied field validation ─────────────────────────────────

    @Test
    @DisplayName("should_rejectPayload_when_soldAtBackdatedBeyondWindow")
    void should_rejectPayload_when_soldAtTooOld() {
        OfflineSaleLineItem lineItem = new OfflineSaleLineItem(
                variantId, locationId, 1, BigDecimal.valueOf(49.99), "SGD", "P", "SKU");
        OfflineSalePayload backdated = new OfflineSalePayload(
                UUID.randomUUID(), List.of(lineItem), "SGD",
                Instant.now().minus(Duration.ofDays(30)));

        assertThatThrownBy(() -> processor.processItem(terminal, backdated, queueRow))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("older than");
        verify(inventoryReservationService, never())
                .resolveConflict(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("should_rejectPayload_when_soldAtInFuture")
    void should_rejectPayload_when_soldAtInFuture() {
        OfflineSaleLineItem lineItem = new OfflineSaleLineItem(
                variantId, locationId, 1, BigDecimal.valueOf(49.99), "SGD", "P", "SKU");
        OfflineSalePayload future = new OfflineSalePayload(
                UUID.randomUUID(), List.of(lineItem), "SGD",
                Instant.now().plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> processor.processItem(terminal, future, queueRow))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("future");
    }

    @Test
    @DisplayName("should_rejectPayload_when_priceNonPositive")
    void should_rejectPayload_when_priceNonPositive() {
        OfflineSaleLineItem lineItem = new OfflineSaleLineItem(
                variantId, locationId, 1, BigDecimal.ZERO, "SGD", "P", "SKU");
        OfflineSalePayload badPrice = new OfflineSalePayload(
                UUID.randomUUID(), List.of(lineItem), "SGD", Instant.now());

        assertThatThrownBy(() -> processor.processItem(terminal, badPrice, queueRow))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("non-positive price");
    }

    @Test
    @DisplayName("should_rejectPayload_when_lineItemCurrencyMismatchesSale")
    void should_rejectPayload_when_currencyMismatch() {
        OfflineSaleLineItem lineItem = new OfflineSaleLineItem(
                variantId, locationId, 1, BigDecimal.valueOf(49.99), "USD", "P", "SKU");
        OfflineSalePayload mixed = new OfflineSalePayload(
                UUID.randomUUID(), List.of(lineItem), "SGD", Instant.now());

        assertThatThrownBy(() -> processor.processItem(terminal, mixed, queueRow))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not match the sale currency");
    }

    @Test
    @DisplayName("should_writeAuditLog_before_markFailed")
    void should_writeAuditLog_before_markFailed() {
        when(posSyncQueueRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.markQueueFailed(queueRow, "test failure reason");

        InOrder inOrder = inOrder(auditService, posSyncQueueRepository);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(posSyncQueueRepository).save(any());
        assertThat(queueRow.getStatus()).isEqualTo(QueueStatus.FAILED);
        assertThat(queueRow.getFailureReason()).isEqualTo("test failure reason");
    }
}
