package com.walmal.inventory.application;

import com.walmal.common.cache.CacheService;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.api.dto.response.StockLevelResponse;
import com.walmal.inventory.application.impl.InventoryQueryServiceImpl;
import com.walmal.inventory.domain.InventoryLocation;
import com.walmal.inventory.domain.InventoryStock;
import com.walmal.inventory.infrastructure.InventoryStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InventoryQueryServiceImpl}.
 * Verifies cache-aside pattern — cache hit short-circuits DB, miss queries DB and populates cache.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryQueryServiceImplTest {

    @Mock InventoryStockRepository stockRepo;
    @Mock CacheService cacheService;

    private InventoryQueryServiceImpl service;

    private UUID variantId;
    private UUID locationId;
    private InventoryLocation location;
    private InventoryStock stock;

    @BeforeEach
    void setUp() {
        service = new InventoryQueryServiceImpl(stockRepo, cacheService);

        variantId = UUID.randomUUID();
        locationId = UUID.randomUUID();

        location = mock(InventoryLocation.class);
        when(location.getId()).thenReturn(locationId);
        when(location.getName()).thenReturn("Main Warehouse");
        when(location.isActive()).thenReturn(true);

        stock = new InventoryStock(variantId, location, 50, 10);
    }

    @Test
    @DisplayName("should_returnCachedStock_when_cacheHit")
    void should_returnCachedStock_when_cacheHit() {
        StockLevelResponse cached = new StockLevelResponse(
                variantId, locationId, "Main Warehouse", 50, 0, 10);
        String cacheKey = "inventory:stock:" + variantId + ":" + locationId;

        when(cacheService.get(eq(cacheKey), eq(StockLevelResponse.class)))
                .thenReturn(Optional.of(cached));

        StockLevelResponse result = service.getStockLevel(variantId, locationId);

        assertThat(result).isEqualTo(cached);
        verify(stockRepo, never()).findByVariantIdAndLocationId(any(), any());
    }

    @Test
    @DisplayName("should_queryDbAndCache_when_cacheMiss")
    void should_queryDbAndCache_when_cacheMiss() {
        String cacheKey = "inventory:stock:" + variantId + ":" + locationId;

        when(cacheService.get(eq(cacheKey), eq(StockLevelResponse.class)))
                .thenReturn(Optional.empty());
        when(stockRepo.findByVariantIdAndLocationId(variantId, locationId))
                .thenReturn(Optional.of(stock));

        StockLevelResponse result = service.getStockLevel(variantId, locationId);

        assertThat(result.variantId()).isEqualTo(variantId);
        assertThat(result.availableQuantity()).isEqualTo(50);
        verify(cacheService).put(eq(cacheKey), eq(result), eq(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_stockNotFound")
    void should_throwResourceNotFoundException_when_stockNotFound() {
        String cacheKey = "inventory:stock:" + variantId + ":" + locationId;
        when(cacheService.get(eq(cacheKey), eq(StockLevelResponse.class)))
                .thenReturn(Optional.empty());
        when(stockRepo.findByVariantIdAndLocationId(variantId, locationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStockLevel(variantId, locationId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("should_returnFalse_when_insufficientStock")
    void should_returnFalse_when_insufficientStock() {
        String cacheKey = "inventory:availability:" + variantId;
        when(cacheService.get(eq(cacheKey), any())).thenReturn(Optional.empty());
        when(stockRepo.findByVariantId(variantId))
                .thenReturn(List.of(stock));  // 50 available

        boolean result = service.checkAvailability(variantId, 100);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should_returnTrue_when_sufficientStockAvailable")
    void should_returnTrue_when_sufficientStockAvailable() {
        String cacheKey = "inventory:availability:" + variantId;
        when(cacheService.get(eq(cacheKey), any())).thenReturn(Optional.empty());
        when(stockRepo.findByVariantId(variantId)).thenReturn(List.of(stock));

        boolean result = service.checkAvailability(variantId, 30);

        assertThat(result).isTrue();
    }
}
