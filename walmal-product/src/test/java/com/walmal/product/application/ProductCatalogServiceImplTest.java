package com.walmal.product.application;

import com.walmal.common.cache.CacheService;
import com.walmal.product.application.dto.VariantSummaryDto;
import com.walmal.product.application.impl.ProductCatalogServiceImpl;
import com.walmal.product.domain.Category;
import com.walmal.product.domain.Product;
import com.walmal.product.domain.ProductStatus;
import com.walmal.product.domain.ProductVariant;
import com.walmal.product.infrastructure.ProductRepository;
import com.walmal.product.infrastructure.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductCatalogServiceImpl}.
 *
 * <p>Key scenarios:
 * <ul>
 *   <li>Cache hit: DB is NOT queried and cached value is returned.</li>
 *   <li>Cache miss: DB is queried and result is stored in cache with correct TTL.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ProductCatalogServiceImplTest {

    @Mock ProductVariantRepository variantRepository;
    @Mock ProductRepository productRepository;
    @Mock CacheService cacheService;

    ProductCatalogServiceImpl service;

    private static final String SKU = "SKU-TEST-001";
    private static final String CACHE_KEY = "product:variant:sku:" + SKU;

    @BeforeEach
    void setUp() {
        service = new ProductCatalogServiceImpl(variantRepository, productRepository, cacheService);
    }

    @Test
    void should_returnCachedVariant_when_cacheHit() {
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        VariantSummaryDto cached = new VariantSummaryDto(
                variantId, productId, SKU, null, "Test Product",
                null, null, ProductStatus.ACTIVE);

        when(cacheService.get(CACHE_KEY, VariantSummaryDto.class))
                .thenReturn(Optional.of(cached));

        Optional<VariantSummaryDto> result = service.findVariantBySku(SKU);

        assertThat(result).isPresent();
        assertThat(result.get().sku()).isEqualTo(SKU);
        assertThat(result.get().variantId()).isEqualTo(variantId);

        // DB must NOT be called when cache hits
        verify(variantRepository, never()).findBySku(anyString());
    }

    @Test
    void should_queryDbAndPopulateCache_when_cacheMiss() {
        when(cacheService.get(CACHE_KEY, VariantSummaryDto.class))
                .thenReturn(Optional.empty());

        // Set up DB response using lenient() for fields not all code paths use
        Category category = mock(Category.class);
        lenient().when(category.getId()).thenReturn(UUID.randomUUID());
        lenient().when(category.getName()).thenReturn("Electronics");

        Product product = mock(Product.class);
        UUID productId = UUID.randomUUID();
        when(product.getId()).thenReturn(productId);
        when(product.getName()).thenReturn("Test Product");
        lenient().when(product.getStatus()).thenReturn(ProductStatus.ACTIVE);
        lenient().when(product.getCategory()).thenReturn(category);

        ProductVariant variant = mock(ProductVariant.class);
        UUID variantId = UUID.randomUUID();
        when(variant.getId()).thenReturn(variantId);
        when(variant.getSku()).thenReturn(SKU);
        lenient().when(variant.getBarcode()).thenReturn(null);
        when(variant.getStatus()).thenReturn(ProductStatus.ACTIVE);
        when(variant.getProduct()).thenReturn(product);
        when(variant.getAttributes()).thenReturn(null);

        when(variantRepository.findBySku(SKU)).thenReturn(Optional.of(variant));

        Optional<VariantSummaryDto> result = service.findVariantBySku(SKU);

        assertThat(result).isPresent();
        assertThat(result.get().sku()).isEqualTo(SKU);
        assertThat(result.get().productId()).isEqualTo(productId);

        // Cache must be populated with correct TTL
        verify(cacheService).put(eq(CACHE_KEY), any(VariantSummaryDto.class), eq(Duration.ofMinutes(5)));
    }

    @Test
    void should_returnEmpty_when_skuNotFound() {
        when(cacheService.get(CACHE_KEY, VariantSummaryDto.class))
                .thenReturn(Optional.empty());
        when(variantRepository.findBySku(SKU)).thenReturn(Optional.empty());

        Optional<VariantSummaryDto> result = service.findVariantBySku(SKU);

        assertThat(result).isEmpty();
        // Cache should NOT be populated for a miss
        verify(cacheService, never()).put(anyString(), any(), any(Duration.class));
    }

    @Test
    void should_returnFalse_when_isVariantActive_andVariantNotFound() {
        UUID variantId = UUID.randomUUID();
        String variantCacheKey = "product:variant:" + variantId;

        when(cacheService.get(variantCacheKey, VariantSummaryDto.class))
                .thenReturn(Optional.empty());
        when(variantRepository.findByIdWithProduct(variantId)).thenReturn(Optional.empty());

        boolean result = service.isVariantActive(variantId);

        assertThat(result).isFalse();
    }
}
