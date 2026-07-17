package com.walmal.pos.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.inventory.application.ConflictOutcome;
import com.walmal.inventory.application.ConflictResolutionResult;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.pos.application.dto.OfflineSaleLineItem;
import com.walmal.pos.application.dto.OfflineSalePayload;
import com.walmal.pos.domain.*;
import com.walmal.pos.domain.event.PosSaleSyncedEvent;
import com.walmal.pos.domain.event.PosSaleSyncedEvent.SaleItemSnapshot;
import com.walmal.pos.domain.event.PosSyncConflictResolvedEvent;
import com.walmal.pos.infrastructure.PosSaleItemRepository;
import com.walmal.pos.infrastructure.PosSaleRepository;
import com.walmal.pos.infrastructure.PosSyncQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.walmal.common.exception.BusinessRuleException;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Internal helper component responsible for per-item two-phase REQUIRES_NEW transaction
 * processing during offline POS sync.
 *
 * <p>This class is NOT a public service interface. It is consumed only by
 * {@link PosSyncServiceImpl} and exists to isolate REQUIRES_NEW transaction propagation
 * from the outer non-transactional {@code submitOfflineSync()} method (SRP).</p>
 *
 * <p>Two-phase protocol per item:
 * <ol>
 *   <li>Phase 1: persist {@code pos_sync_queue} row (REQUIRES_NEW — always commits).
 *       Gives operators visibility into all submitted items.</li>
 *   <li>Phase 2 ({@link #processItem}): create {@code pos_sale} + {@code pos_sale_items},
 *       call {@code resolveConflict()} per line item, update sync_status and queue status,
 *       publish event. All in a single REQUIRES_NEW transaction.</li>
 *   <li>Catch path ({@link #markQueueFailed}): if Phase 2 rolls back, mark the Phase 1
 *       queue row as FAILED in a new REQUIRES_NEW transaction, after writing audit_log.</li>
 * </ol>
 * </p>
 *
 * <p>DIP compliance: uses {@code DomainEventPublisher} interface — {@code RabbitTemplate}
 * is never injected here.</p>
 */
@Component
public class PosSyncItemProcessor {

    private static final Logger log = LoggerFactory.getLogger(PosSyncItemProcessor.class);

    /** Tolerance for a terminal clock running ahead of the server. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    /** Reject offline sales claiming to be older than this — bounds how far a
     *  terminal can backdate {@code soldAt} to win POS-priority conflict resolution. */
    @Value("${pos.sync.max-offline-age-days:7}")
    private int maxOfflineAgeDays;

    /** Max % a device-reported offline price may deviate (either direction) from the
     *  current server price before the payload is rejected — catches both over- and
     *  under-charge fraud while tolerating minor drift during the offline window. */
    @Value("${pos.sync.max-price-deviation-pct:20}")
    private int maxPriceDeviationPct;

    private final PosSaleRepository posSaleRepository;
    private final PosSaleItemRepository posSaleItemRepository;
    private final PosSyncQueueRepository posSyncQueueRepository;
    private final InventoryReservationService inventoryReservationService;
    private final ProductPricingService productPricingService;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public PosSyncItemProcessor(
            PosSaleRepository posSaleRepository,
            PosSaleItemRepository posSaleItemRepository,
            PosSyncQueueRepository posSyncQueueRepository,
            InventoryReservationService inventoryReservationService,
            ProductPricingService productPricingService,
            AuditService auditService,
            DomainEventPublisher eventPublisher,
            ObjectMapper objectMapper) {
        this.posSaleRepository = posSaleRepository;
        this.posSaleItemRepository = posSaleItemRepository;
        this.posSyncQueueRepository = posSyncQueueRepository;
        this.inventoryReservationService = inventoryReservationService;
        this.productPricingService = productPricingService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Phase 2: Process a single offline sale payload.
     *
     * <p>Runs in a REQUIRES_NEW transaction. If any step throws, the entire phase 2
     * rolls back (pos_sale and pos_sale_items rows are lost). The caller catches the
     * exception and calls {@link #markQueueFailed} to persist the failure.</p>
     *
     * @param terminal  the submitting terminal
     * @param payload   the offline sale payload
     * @param queueRow  the Phase 1 queue row (PENDING status)
     * @return result with outcome and success flag
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncItemResult processItem(PosTerminal terminal, OfflineSalePayload payload,
                                       PosSyncQueue queueRow) {

        // Step 0: validate device-supplied fields before trusting them. Throwing
        // here rolls back Phase 2 and the caller marks the queue row FAILED for
        // operator review — a rejected payload never resolves conflicts or records
        // a sale. (Security review finding #7.)
        validatePayload(payload);

        // Step 1: compute totalAmount from line items
        BigDecimal totalAmount = payload.items().stream()
                .map(item -> item.priceAtSale().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Step 2: create pos_sale (OFFLINE, PENDING — sync in progress)
        PosSale sale = new PosSale(
                terminal,
                null,   // onlineOrderId is null for offline sales
                payload.soldAt(),
                totalAmount,
                payload.currency(),
                SaleMode.OFFLINE,
                SyncStatus.PENDING,
                null);  // cashierId not available in offline payload for MVP
        sale = posSaleRepository.save(sale);

        // Step 3: create pos_sale_items
        List<SaleItemSnapshot> eventItems = new ArrayList<>();
        for (OfflineSaleLineItem lineItem : payload.items()) {
            BigDecimal subtotal = lineItem.priceAtSale()
                    .multiply(BigDecimal.valueOf(lineItem.quantity()));
            PosSaleItem item = new PosSaleItem(
                    sale,
                    lineItem.variantId(),
                    lineItem.productNameSnapshot(),
                    lineItem.skuSnapshot(),
                    lineItem.quantity(),
                    lineItem.priceAtSale(),
                    lineItem.currency(),
                    subtotal,
                    lineItem.locationId());
            posSaleItemRepository.save(item);
            eventItems.add(new SaleItemSnapshot(lineItem.variantId(), lineItem.quantity(),
                    lineItem.skuSnapshot()));
        }

        // Step 4: call resolveConflict() per line item, track worst outcome
        ConflictOutcome worstOutcome = ConflictOutcome.NO_CONFLICT;
        UUID cancelledOrderId = null;

        for (OfflineSaleLineItem lineItem : payload.items()) {
            // webOrderId is always null for MVP — resolveConflict() does an internal lookup
            ConflictResolutionResult result = inventoryReservationService.resolveConflict(
                    sale.getId(),
                    lineItem.variantId(),
                    lineItem.locationId(),
                    lineItem.quantity(),
                    payload.soldAt(),
                    null);

            // Track worst outcome: POS_PRIORITY or BUFFER_EXHAUSTED indicate a conflict
            if (result.outcome() == ConflictOutcome.POS_PRIORITY
                    || result.outcome() == ConflictOutcome.BUFFER_EXHAUSTED) {
                worstOutcome = result.outcome();
                if (result.cancelledOrderId() != null) {
                    cancelledOrderId = result.cancelledOrderId();
                }
            }
        }

        // Step 5: update sync_status and queue status based on worst outcome
        boolean hasConflict = (worstOutcome == ConflictOutcome.POS_PRIORITY
                || worstOutcome == ConflictOutcome.BUFFER_EXHAUSTED);

        if (hasConflict) {
            sale.markConflictResolved();
        } else {
            sale.markSynced();
        }
        queueRow.markProcessed();

        posSaleRepository.save(sale);
        posSyncQueueRepository.save(queueRow);

        // Step 6: publish appropriate event
        Instant now = Instant.now();
        if (hasConflict) {
            eventPublisher.publish(
                    new PosSyncConflictResolvedEvent(
                            terminal.getId(), sale.getId(), cancelledOrderId,
                            worstOutcome.name(), worstOutcome, now),
                    "pos.sync.conflict.resolved");
        } else {
            eventPublisher.publish(
                    new PosSaleSyncedEvent(terminal.getId(), sale.getId(), eventItems, now),
                    "pos.sale.synced");
        }

        log.info("Offline sale synced: saleId={} terminalId={} outcome={}",
                sale.getId(), terminal.getId(), worstOutcome);

        return new SyncItemResult(payload.localId(), worstOutcome, true, null);
    }

    /**
     * Rejects payloads whose device-supplied fields cannot be trusted:
     * <ul>
     *   <li><b>soldAt</b> in the future (beyond clock skew) or older than the sync
     *       window. This bounds the backdating attack — the conflict-resolution rule
     *       is "earlier POS sale wins", so an unbounded past {@code soldAt} could
     *       cancel a legitimate web reservation.</li>
     *   <li><b>priceAtSale</b> non-positive, or a line-item currency inconsistent
     *       with the sale currency (which would make the total meaningless).</li>
     * </ul>
     *
     * <p>Price reconciliation: the device-reported {@code priceAtSale} is kept (it is
     * what the customer actually paid at the terminal) but must be within
     * {@code pos.sync.max-price-deviation-pct} of the current server price — catching
     * both over- and under-charge fraud while tolerating minor price drift during the
     * offline window. A variant with no current server price cannot be reconciled and
     * is rejected for operator review.</p>
     */
    private void validatePayload(OfflineSalePayload payload) {
        Instant now = Instant.now();
        Instant soldAt = payload.soldAt();
        if (soldAt == null) {
            throw new BusinessRuleException("Offline sale soldAt is required");
        }
        if (soldAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new BusinessRuleException("Offline sale soldAt is in the future: " + soldAt);
        }
        if (soldAt.isBefore(now.minus(Duration.ofDays(maxOfflineAgeDays)))) {
            throw new BusinessRuleException(
                    "Offline sale soldAt " + soldAt + " is older than the "
                    + maxOfflineAgeDays + "-day offline sync window");
        }
        for (OfflineSaleLineItem item : payload.items()) {
            if (item.priceAtSale() == null || item.priceAtSale().signum() <= 0) {
                throw new BusinessRuleException(
                        "Offline sale line item has a non-positive price: variant " + item.variantId());
            }
            if (item.currency() == null || !item.currency().equalsIgnoreCase(payload.currency())) {
                throw new BusinessRuleException(
                        "Offline sale line item currency " + item.currency()
                        + " does not match the sale currency " + payload.currency());
            }
            PriceDto serverPrice = productPricingService.getCurrentPrice(item.variantId())
                    .orElseThrow(() -> new BusinessRuleException(
                            "No current server price to reconcile the offline sale against for variant "
                            + item.variantId()));
            if (!serverPrice.currency().equalsIgnoreCase(item.currency())) {
                throw new BusinessRuleException(
                        "Offline price currency " + item.currency() + " differs from the server price currency "
                        + serverPrice.currency() + " for variant " + item.variantId());
            }
            if (!withinTolerance(item.priceAtSale(), serverPrice.amount(), maxPriceDeviationPct)) {
                throw new BusinessRuleException(
                        "Offline price " + item.priceAtSale() + " deviates more than " + maxPriceDeviationPct
                        + "% from the server price " + serverPrice.amount() + " for variant " + item.variantId());
            }
        }
    }

    /**
     * True if {@code device} is within {@code pct}% of {@code server} in either
     * direction. Pure and public for unit testing. A non-positive server price is
     * never within tolerance (nothing sensible to reconcile against).
     */
    public static boolean withinTolerance(BigDecimal device, BigDecimal server, int pct) {
        if (server == null || server.signum() <= 0 || device == null) {
            return false;
        }
        BigDecimal allowed = server.multiply(BigDecimal.valueOf(pct)).movePointLeft(2).abs();
        return device.subtract(server).abs().compareTo(allowed) <= 0;
    }

    /**
     * Catch-path: marks a Phase 1 queue row as FAILED in a new REQUIRES_NEW transaction.
     *
     * <p>Audit log is written BEFORE the FAILED status mutation (architecture rule).</p>
     *
     * @param queueRow the Phase 1 queue row to mark as failed
     * @param reason   human-readable failure reason
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markQueueFailed(PosSyncQueue queueRow, String reason) {
        // Audit BEFORE mutation — architecture rule (CLAUDE.md)
        auditService.log(new AuditEntry(
                "pos_sync_queue",
                queueRow.getId(),
                AuditAction.STATUS_CHANGE,
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"FAILED\",\"failure_reason\":\"" + escapeJson(reason) + "\"}",
                "system:pos-sync-processor"));

        queueRow.markFailed(reason);
        posSyncQueueRepository.save(queueRow);

        log.warn("Sync queue row marked FAILED: queueId={} reason={}", queueRow.getId(), reason);
    }

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Result of processing a single offline sale payload.
     *
     * @param localId       the device-generated UUID from the payload
     * @param outcome       the conflict outcome from inventory resolution
     * @param success       true if Phase 2 completed without exception
     * @param failureReason non-null only when success=false
     */
    public record SyncItemResult(
            UUID localId,
            ConflictOutcome outcome,
            boolean success,
            String failureReason
    ) {
        public static SyncItemResult failure(UUID localId, String reason) {
            return new SyncItemResult(localId, null, false, reason);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
