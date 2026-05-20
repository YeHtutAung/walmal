package com.walmal.inventory.application.impl;

import com.walmal.common.cache.CacheService;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.api.dto.response.StockAvailabilityResponse;
import com.walmal.inventory.api.dto.response.StockLevelResponse;
import com.walmal.inventory.application.InventoryQueryService;
import com.walmal.inventory.domain.InventoryStock;
import com.walmal.inventory.infrastructure.InventoryStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link InventoryQueryService}.
 *
 * <p>Cache-aside pattern: read from CacheService first; on miss, query DB and populate cache.
 * All caching uses {@code CacheService} — {@code RedisTemplate} is never injected here (DIP).</p>
 */
@Service
public class InventoryQueryServiceImpl implements InventoryQueryService {

    private static final Duration STOCK_TTL = Duration.ofSeconds(30);
    private static final Duration AVAILABILITY_TTL = Duration.ofSeconds(60);

    private final InventoryStockRepository stockRepo;
    private final CacheService cacheService;

    public InventoryQueryServiceImpl(InventoryStockRepository stockRepo,
                                      CacheService cacheService) {
        this.stockRepo = stockRepo;
        this.cacheService = cacheService;
    }

    // ── getStockLevel ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public StockLevelResponse getStockLevel(UUID variantId, UUID locationId) {
        String cacheKey = "inventory:stock:" + variantId + ":" + locationId;

        return cacheService.get(cacheKey, StockLevelResponse.class)
                .orElseGet(() -> {
                    InventoryStock stock = stockRepo
                            .findByVariantIdAndLocationId(variantId, locationId)
                            .orElseThrow(() -> new ResourceNotFoundException(
                                    "InventoryStock", variantId + "@" + locationId));

                    StockLevelResponse response = toStockLevelResponse(stock);
                    cacheService.put(cacheKey, response, STOCK_TTL);
                    return response;
                });
    }

    // ── checkAvailability ─────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public boolean checkAvailability(UUID variantId, int quantity) {
        StockAvailabilityResponse availability = getAggregatedAvailability(variantId);
        return availability.totalAvailable() >= quantity;
    }

    // ── getAggregatedAvailability ─────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public StockAvailabilityResponse getAggregatedAvailability(UUID variantId) {
        String cacheKey = "inventory:availability:" + variantId;

        return cacheService.get(cacheKey, StockAvailabilityResponse.class)
                .orElseGet(() -> {
                    List<InventoryStock> stocks = stockRepo.findByVariantId(variantId);

                    List<StockLevelResponse> locationBreakdown = stocks.stream()
                            .filter(s -> s.getLocation().isActive())
                            .map(this::toStockLevelResponse)
                            .collect(Collectors.toList());

                    int totalAvailable = locationBreakdown.stream()
                            .mapToInt(StockLevelResponse::availableQuantity)
                            .sum();
                    int totalReserved = locationBreakdown.stream()
                            .mapToInt(StockLevelResponse::reservedQuantity)
                            .sum();

                    StockAvailabilityResponse response = new StockAvailabilityResponse(
                            variantId, totalAvailable, totalReserved,
                            totalAvailable > 0, locationBreakdown);
                    cacheService.put(cacheKey, response, AVAILABILITY_TTL);
                    return response;
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private StockLevelResponse toStockLevelResponse(InventoryStock stock) {
        return new StockLevelResponse(
                stock.getVariantId(),
                stock.getLocation().getId(),
                stock.getLocation().getName(),
                stock.getAvailableQuantity(),
                stock.getReservedQuantity(),
                stock.getLowStockThreshold());
    }
}
