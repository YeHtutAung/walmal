package com.walmal.inventory.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.application.InventoryAdjustmentService;
import com.walmal.inventory.domain.*;
import com.walmal.inventory.domain.event.InventoryStockExhaustedEvent;
import com.walmal.inventory.domain.event.InventoryStockLowEvent;
import com.walmal.inventory.infrastructure.InventoryLocationRepository;
import com.walmal.inventory.infrastructure.InventoryMovementRepository;
import com.walmal.inventory.infrastructure.InventoryStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementation of {@link InventoryAdjustmentService}.
 *
 * <p>DIP compliance: RabbitMQ via {@code DomainEventPublisher}, Redis via {@code CacheService},
 * audit via {@code AuditService}.</p>
 */
@Service
public class InventoryAdjustmentServiceImpl implements InventoryAdjustmentService {

    private final InventoryStockRepository stockRepo;
    private final InventoryMovementRepository movementRepo;
    private final InventoryLocationRepository locationRepo;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;
    private final CacheService cacheService;

    public InventoryAdjustmentServiceImpl(
            InventoryStockRepository stockRepo,
            InventoryMovementRepository movementRepo,
            InventoryLocationRepository locationRepo,
            AuditService auditService,
            DomainEventPublisher eventPublisher,
            CacheService cacheService) {
        this.stockRepo = stockRepo;
        this.movementRepo = movementRepo;
        this.locationRepo = locationRepo;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
    }

    // ── adjustStock ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void adjustStock(UUID variantId, UUID locationId, int delta,
                             String reason, String performedBy) {
        InventoryStock stock = stockRepo.findByVariantIdAndLocationId(variantId, locationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InventoryStock", variantId + "@" + locationId));

        InventoryLocation location = stock.getLocation();

        // Audit BEFORE destructive mutations (negative delta = stock decrease)
        if (delta < 0) {
            auditService.log(new AuditEntry(
                    "inventory_stock",
                    stock.getId(),
                    AuditAction.UPDATE,
                    "{\"availableQuantity\":" + stock.getAvailableQuantity() + "}",
                    "{\"availableQuantity\":" + (stock.getAvailableQuantity() + delta)
                    + ",\"reason\":\"" + reason + "\"}",
                    performedBy));
        }

        stock.applyDelta(delta);
        stockRepo.save(stock);

        // Write movement — RECEIPT for pure inflows, ADJUSTMENT otherwise
        MovementType movType = (delta > 0) ? MovementType.RECEIPT : MovementType.ADJUSTMENT;
        movementRepo.save(new InventoryMovement(
                variantId, location, movType, delta, null, performedBy));

        // Evict cache
        evictStockCache(variantId, locationId);

        // Publish threshold events
        publishStockThresholdEvents(stock);
    }

    // ── transferStock ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void transferStock(UUID variantId, UUID fromLocationId, UUID toLocationId,
                               int quantity, String performedBy) {
        if (quantity <= 0) {
            throw new BusinessRuleException("Transfer quantity must be positive");
        }

        InventoryLocation fromLocation = locationRepo.findById(fromLocationId)
                .filter(InventoryLocation::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocation", fromLocationId));

        InventoryLocation toLocation = locationRepo.findById(toLocationId)
                .filter(InventoryLocation::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocation", toLocationId));

        InventoryStock fromStock = stockRepo.findByVariantIdAndLocationId(variantId, fromLocationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InventoryStock", variantId + "@" + fromLocationId));

        if (!fromStock.canReserve(quantity)) {
            throw new BusinessRuleException(
                    "Insufficient stock for transfer: available=" + fromStock.getAvailableQuantity()
                    + ", requested=" + quantity);
        }

        // Audit BEFORE source deduction (destructive operation)
        auditService.log(new AuditEntry(
                "inventory_stock",
                fromStock.getId(),
                AuditAction.UPDATE,
                "{\"availableQuantity\":" + fromStock.getAvailableQuantity() + "}",
                "{\"availableQuantity\":" + (fromStock.getAvailableQuantity() - quantity)
                + ",\"transferTo\":\"" + toLocationId + "\"}",
                performedBy));

        // Decrement source
        fromStock.applyDelta(-quantity);
        stockRepo.save(fromStock);
        movementRepo.save(new InventoryMovement(
                variantId, fromLocation, MovementType.TRANSFER_OUT, -quantity, null, performedBy));

        // Increment destination — create stock row if it doesn't exist
        InventoryStock toStock = stockRepo.findByVariantIdAndLocationId(variantId, toLocationId)
                .orElseGet(() -> new InventoryStock(variantId, toLocation, 0, 10));
        toStock.applyDelta(quantity);
        stockRepo.save(toStock);
        movementRepo.save(new InventoryMovement(
                variantId, toLocation, MovementType.TRANSFER_IN, quantity, null, performedBy));

        // Evict both caches
        evictStockCache(variantId, fromLocationId);
        evictStockCache(variantId, toLocationId);

        // Publish threshold events for source
        publishStockThresholdEvents(fromStock);
    }

    // ── updateLowStockThreshold ───────────────────────────────────────────────

    @Override
    @Transactional
    public void updateLowStockThreshold(UUID variantId, UUID locationId,
                                         int threshold, String performedBy) {
        if (threshold < 0) {
            throw new BusinessRuleException("Threshold must be >= 0");
        }
        InventoryStock stock = stockRepo.findByVariantIdAndLocationId(variantId, locationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InventoryStock", variantId + "@" + locationId));

        stock.setLowStockThreshold(threshold);
        stockRepo.save(stock);

        // Evict cache — threshold change may affect availability response
        evictStockCache(variantId, locationId);
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
