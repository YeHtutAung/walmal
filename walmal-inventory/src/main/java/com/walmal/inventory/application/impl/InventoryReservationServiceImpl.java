package com.walmal.inventory.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ConcurrencyConflictException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.inventory.domain.*;
import com.walmal.inventory.domain.event.*;
import com.walmal.inventory.infrastructure.*;
import com.walmal.product.application.ProductCatalogService;
import jakarta.persistence.OptimisticLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of {@link InventoryReservationService}.
 *
 * <p>Concurrency: optimistic locking ({@code @Version}) on the normal reservation path.
 * Three retries with increasing back-off. POS conflict override uses a direct UPDATE
 * WHERE clause (see {@code resolveConflict}).</p>
 *
 * <p>DIP compliance: RabbitMQ via {@code DomainEventPublisher}, Redis via {@code CacheService},
 * audit via {@code AuditService}. No direct framework class injected.</p>
 */
@Service
public class InventoryReservationServiceImpl implements InventoryReservationService {

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MILLIS = {50L, 100L, 200L};
    private static final Duration RESERVATION_EXPIRY = Duration.ofMinutes(30);

    private final InventoryStockRepository stockRepo;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryMovementRepository movementRepo;
    private final InventoryLocationRepository locationRepo;
    private final ProductCatalogService productCatalogService;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;
    private final CacheService cacheService;

    public InventoryReservationServiceImpl(
            InventoryStockRepository stockRepo,
            InventoryReservationRepository reservationRepo,
            InventoryMovementRepository movementRepo,
            InventoryLocationRepository locationRepo,
            ProductCatalogService productCatalogService,
            AuditService auditService,
            DomainEventPublisher eventPublisher,
            CacheService cacheService) {
        this.stockRepo = stockRepo;
        this.reservationRepo = reservationRepo;
        this.movementRepo = movementRepo;
        this.locationRepo = locationRepo;
        this.productCatalogService = productCatalogService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
    }

    // ── reserveStock ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void reserveStock(UUID orderId, List<ReservationLineItem> items) {
        for (ReservationLineItem item : items) {
            // 1. Validate variant is active via ProductCatalogService (cross-module interface only)
            if (!productCatalogService.isVariantActive(item.variantId())) {
                throw new BusinessRuleException(
                        "Variant " + item.variantId() + " is not active");
            }
            // 2. Reserve with optimistic lock retry
            reserveWithRetry(orderId, item);
        }
    }

    private void reserveWithRetry(UUID orderId, ReservationLineItem item) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                doReserve(orderId, item);
                return;
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(BACKOFF_MILLIS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ConcurrencyConflictException(
                                "Interrupted during reservation retry for variant: " + item.variantId());
                    }
                } else {
                    throw new ConcurrencyConflictException(
                            "Stock reservation failed after " + MAX_RETRIES
                            + " retries due to concurrent updates for variant: " + item.variantId());
                }
            }
        }
    }

    @Transactional
    protected void doReserve(UUID orderId, ReservationLineItem item) {
        InventoryStock stock = stockRepo.findByVariantIdAndLocationId(
                item.variantId(), item.locationId())
                .orElseThrow(() -> new BusinessRuleException(
                        "No stock record for variant=" + item.variantId()
                        + " at location=" + item.locationId()));

        if (!stock.canReserve(item.quantity())) {
            throw new BusinessRuleException(
                    "Insufficient stock: available=" + stock.getAvailableQuantity()
                    + ", requested=" + item.quantity()
                    + " for variant=" + item.variantId());
        }

        // 3. Stock mutation
        stock.reserve(item.quantity());
        stockRepo.save(stock);

        // 4. Create reservation
        Instant expiresAt = Instant.now().plus(RESERVATION_EXPIRY);
        InventoryReservation reservation = new InventoryReservation(
                orderId, item.variantId(), stock.getLocation(), item.quantity(), expiresAt);
        reservationRepo.save(reservation);

        // 5. Write movement
        movementRepo.save(new InventoryMovement(
                item.variantId(), stock.getLocation(),
                MovementType.RESERVATION, -item.quantity(),
                orderId, "system:reservation"));

        // 6. Evict cache
        evictStockCache(item.variantId(), item.locationId());

        // 7. Check thresholds and publish events
        publishStockThresholdEvents(stock);
    }

    // ── confirmReservation ────────────────────────────────────────────────────

    @Override
    @Transactional
    public void confirmReservation(UUID orderId) {
        List<InventoryReservation> pending = reservationRepo
                .findByOrderIdAndStatus(orderId, ReservationStatus.PENDING);

        if (pending.isEmpty()) {
            throw new ResourceNotFoundException("PENDING reservations for order", orderId);
        }

        for (InventoryReservation reservation : pending) {
            InventoryStock stock = stockRepo.findByVariantIdAndLocationId(
                    reservation.getVariantId(), reservation.getLocation().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "InventoryStock", reservation.getVariantId()));

            // Stock permanently leaves system — decrement reserved_quantity
            stock.confirm(reservation.getQuantity());
            stockRepo.save(stock);

            // Confirm reservation status
            reservation.confirm();
            reservationRepo.save(reservation);

            // Write SALE movement
            movementRepo.save(new InventoryMovement(
                    reservation.getVariantId(), reservation.getLocation(),
                    MovementType.SALE, 0,  // delta=0: stock was already decremented at reserve time
                    orderId, "system:confirmation"));

            // Evict cache
            evictStockCache(reservation.getVariantId(), reservation.getLocation().getId());

            // Publish confirmed event
            eventPublisher.publish(
                    new InventoryReservationConfirmedEvent(
                            orderId, reservation.getVariantId(),
                            reservation.getLocation().getId(), reservation.getQuantity()),
                    "inventory.reservation.confirmed");
        }
    }

    // ── releaseReservation ────────────────────────────────────────────────────

    @Override
    @Transactional
    public void releaseReservation(UUID orderId, ConflictReason conflictReason) {
        List<InventoryReservation> pending = reservationRepo
                .findByOrderIdAndStatus(orderId, ReservationStatus.PENDING);

        if (pending.isEmpty()) {
            throw new ResourceNotFoundException("PENDING reservations for order", orderId);
        }

        for (InventoryReservation reservation : pending) {
            InventoryStock stock = stockRepo.findByVariantIdAndLocationId(
                    reservation.getVariantId(), reservation.getLocation().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "InventoryStock", reservation.getVariantId()));

            // Audit BEFORE mutation (architecture rule)
            auditService.log(new AuditEntry(
                    "inventory_reservations",
                    reservation.getId(),
                    AuditAction.STATUS_CHANGE,
                    "{\"status\":\"PENDING\"}",
                    "{\"status\":\"RELEASED\",\"conflictReason\":\"" + conflictReason.name() + "\"}",
                    "system:release"));

            // Return stock to available pool
            stock.release(reservation.getQuantity());
            stockRepo.save(stock);

            // Transition reservation state
            reservation.release(conflictReason);
            reservationRepo.save(reservation);

            // Write RELEASE movement
            movementRepo.save(new InventoryMovement(
                    reservation.getVariantId(), reservation.getLocation(),
                    MovementType.RELEASE, reservation.getQuantity(),
                    orderId, "system:release"));

            // Evict cache
            evictStockCache(reservation.getVariantId(), reservation.getLocation().getId());

            // Publish released event
            eventPublisher.publish(
                    new InventoryReservationReleasedEvent(
                            orderId, reservation.getVariantId(),
                            reservation.getLocation().getId(),
                            reservation.getQuantity(), conflictReason),
                    "inventory.reservation.released");
        }
    }

    // ── resolveConflict ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public void resolveConflict(UUID posSaleId, UUID variantId, UUID locationId,
                                 int quantity, Instant posSaleTimestamp, UUID webOrderId) {

        // Step 2: POS wins if its timestamp is before the web reservation's createdAt
        if (webOrderId != null) {
            InventoryReservation webReservation = reservationRepo
                    .findByOrderIdAndVariantIdAndStatus(webOrderId, variantId, ReservationStatus.PENDING)
                    .orElse(null);

            if (webReservation != null
                    && posSaleTimestamp.isBefore(webReservation.getCreatedAt())) {
                // POS wins — release web reservation, then deduct stock directly
                releaseReservation(webOrderId, ConflictReason.POS_PRIORITY);
                deductStockDirect(variantId, locationId, quantity, posSaleId, "system:pos-sync");
                return;
            }
        }

        // Step 3: Attempt deduction from primary location
        int updated = stockRepo.decrementStockDirect(variantId, locationId, quantity);
        if (updated == 1) {
            writeMovementAndEvict(variantId, locationId, quantity, posSaleId);
            return;
        }

        // Step 4: Try buffer locations
        List<InventoryLocation> bufferLocations = locationRepo.findByBufferLocationTrueAndActiveTrue();
        for (InventoryLocation buffer : bufferLocations) {
            int bufferUpdated = stockRepo.decrementStockDirect(variantId, buffer.getId(), quantity);
            if (bufferUpdated == 1) {
                writeMovementAndEvict(variantId, buffer.getId(), quantity, posSaleId);
                return;
            }
        }

        // Step 5: Buffer exhausted — release web reservation with BUFFER_EXHAUSTED
        if (webOrderId != null) {
            auditService.log(new AuditEntry(
                    "inventory_reservations",
                    webOrderId,
                    AuditAction.STATUS_CHANGE,
                    "{\"status\":\"PENDING\"}",
                    "{\"status\":\"RELEASED\",\"conflictReason\":\"BUFFER_EXHAUSTED\"}",
                    "system:pos-sync"));
            releaseReservation(webOrderId, ConflictReason.BUFFER_EXHAUSTED);
        }
        // POS sale recorded as debt — warehouse reconciliation handles it (out of scope for MVP)
    }

    private void deductStockDirect(UUID variantId, UUID locationId,
                                    int quantity, UUID referenceId, String performedBy) {
        int updated = stockRepo.decrementStockDirect(variantId, locationId, quantity);
        if (updated == 1) {
            writeMovementAndEvict(variantId, locationId, quantity, referenceId);
        }
        // If 0 rows — stock insufficient after POS release, handled upstream
    }

    private void writeMovementAndEvict(UUID variantId, UUID locationId,
                                        int quantity, UUID referenceId) {
        InventoryLocation location = locationRepo.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocation", locationId));
        movementRepo.save(new InventoryMovement(
                variantId, location, MovementType.SALE, -quantity,
                referenceId, "system:pos-sync"));
        evictStockCache(variantId, locationId);

        // Check thresholds after direct update — reload fresh state
        stockRepo.findByVariantIdAndLocationId(variantId, locationId)
                .ifPresent(this::publishStockThresholdEvents);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void evictStockCache(UUID variantId, UUID locationId) {
        cacheService.evict("inventory:stock:" + variantId + ":" + locationId);
        cacheService.evict("inventory:availability:" + variantId);
    }

    private void publishStockThresholdEvents(InventoryStock stock) {
        if (stock.isExhausted()) {
            eventPublisher.publish(
                    new InventoryStockExhaustedEvent(
                            stock.getVariantId(), stock.getLocation().getId()),
                    "inventory.stock.exhausted");
        } else if (stock.isBelowLowStockThreshold()) {
            eventPublisher.publish(
                    new InventoryStockLowEvent(
                            stock.getVariantId(), stock.getLocation().getId(),
                            stock.getAvailableQuantity(), stock.getLowStockThreshold()),
                    "inventory.stock.low");
        }
    }
}
