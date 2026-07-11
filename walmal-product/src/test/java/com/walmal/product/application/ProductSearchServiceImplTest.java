package com.walmal.product.application;

import com.walmal.common.cache.CacheService;
import com.walmal.product.application.dto.ProductSummaryDto;
import com.walmal.product.application.impl.ProductSearchServiceImpl;
import com.walmal.product.infrastructure.CategoryRepository;
import com.walmal.product.infrastructure.ProductImageRepository;
import com.walmal.product.infrastructure.ProductImageStorageAdapter;
import com.walmal.product.infrastructure.ProductPriceRepository;
import com.walmal.product.infrastructure.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductSearchServiceImpl#searchProducts}.
 *
 * <p>Key scenarios:
 * <ul>
 *   <li>Blank query (empty or whitespace-only): the list-all path — the admin
 *       products list page depends on this exact behavior, so it must call
 *       {@code findAll} and never the widened search query.</li>
 *   <li>Non-blank query: trimmed, lowercased, and {@code %}-wrapped before
 *       being passed to the widened name/brand/SKU/barcode search.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ProductSearchServiceImplTest {

    @Mock ProductRepository productRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock ProductPriceRepository priceRepository;
    @Mock ProductImageRepository imageRepository;
    @Mock ProductImageStorageAdapter imageStorageAdapter;
    @Mock CacheService cacheService;

    ProductSearchServiceImpl service;

    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new ProductSearchServiceImpl(productRepository, categoryRepository, priceRepository, imageRepository, imageStorageAdapter, cacheService);
    }

    @Test
    @DisplayName("Empty query uses findAll (admin list-all path), never the widened search")
    void should_listAllProducts_when_queryIsEmpty() {
        when(productRepository.findAll(pageable)).thenReturn(Page.empty());

        Page<ProductSummaryDto> result = service.searchProducts("", pageable);

        assertThat(result).isEmpty();
        verify(productRepository).findAll(pageable);
        verify(productRepository, never()).searchByNameBrandSkuOrBarcode(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("Whitespace-only query uses findAll (admin list-all path), never the widened search")
    void should_listAllProducts_when_queryIsWhitespaceOnly() {
        when(productRepository.findAll(pageable)).thenReturn(Page.empty());

        Page<ProductSummaryDto> result = service.searchProducts("   ", pageable);

        assertThat(result).isEmpty();
        verify(productRepository).findAll(pageable);
        verify(productRepository, never()).searchByNameBrandSkuOrBarcode(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("Null query uses findAll (admin list-all path), never the widened search")
    void should_listAllProducts_when_queryIsNull() {
        when(productRepository.findAll(pageable)).thenReturn(Page.empty());

        Page<ProductSummaryDto> result = service.searchProducts(null, pageable);

        assertThat(result).isEmpty();
        verify(productRepository).findAll(pageable);
        verify(productRepository, never()).searchByNameBrandSkuOrBarcode(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("Non-blank query is trimmed, lowercased, %-wrapped and sent to the widened search")
    void should_searchByNameBrandSkuOrBarcodeWithNormalizedPattern_when_queryIsNonBlank() {
        when(productRepository.searchByNameBrandSkuOrBarcode(eq("%abc%"), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<ProductSummaryDto> result = service.searchProducts("  AbC ", pageable);

        assertThat(result).isEmpty();
        verify(productRepository).searchByNameBrandSkuOrBarcode(eq("%abc%"), eq(pageable));
        verify(productRepository, never()).findAll(any(Pageable.class));
    }
}
