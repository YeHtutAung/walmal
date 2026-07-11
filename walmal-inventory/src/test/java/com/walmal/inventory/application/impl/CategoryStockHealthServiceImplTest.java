package com.walmal.inventory.application.impl;

import com.walmal.inventory.api.dto.response.CategoryStockHealthDto;
import com.walmal.inventory.domain.InventoryStock;
import com.walmal.inventory.domain.StockHealthStatus;
import com.walmal.inventory.infrastructure.InventoryStockRepository;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.dto.CategoryProductVariantRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CategoryStockHealthServiceImpl aggregation logic.
 */
@ExtendWith(MockitoExtension.class)
class CategoryStockHealthServiceImplTest {

    @Mock private ProductCatalogService productCatalogService;
    @Mock private InventoryStockRepository stockRepository;

    private CategoryStockHealthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CategoryStockHealthServiceImpl(productCatalogService, stockRepository);
    }

    @Test
    @DisplayName("should_returnZeroedCategory_when_categoryHasNoProducts")
    void should_returnZeroedCategory_when_categoryHasNoProducts() {
        UUID catId = UUID.randomUUID();
        when(productCatalogService.getAllCategoryProductVariantMappings()).thenReturn(List.of(
                new CategoryProductVariantRow(catId, "Empty", null, null)
        ));
        when(stockRepository.findByVariantIdIn(List.of())).thenReturn(List.of());

        List<CategoryStockHealthDto> result = service.getStockHealthByCategory();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(new CategoryStockHealthDto(catId, "Empty", 0, 0, 0, 0));
    }

    @Test
    @DisplayName("should_countVariantLessProductTowardProductCountOnly_when_productHasNoVariant")
    void should_countVariantLessProductTowardProductCountOnly_when_productHasNoVariant() {
        UUID catId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productCatalogService.getAllCategoryProductVariantMappings()).thenReturn(List.of(
                new CategoryProductVariantRow(catId, "Cat", productId, null)
        ));
        when(stockRepository.findByVariantIdIn(List.of())).thenReturn(List.of());

        CategoryStockHealthDto dto = service.getStockHealthByCategory().get(0);

        assertThat(dto.productCount()).isEqualTo(1);
        assertThat(dto.okCount() + dto.lowCount() + dto.criticalCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("should_tallyHealthCountsPerCategory_when_stockRowsMatchVariants")
    void should_tallyHealthCountsPerCategory_when_stockRowsMatchVariants() {
        UUID catId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        when(productCatalogService.getAllCategoryProductVariantMappings()).thenReturn(List.of(
                new CategoryProductVariantRow(catId, "Cat", productId, variantId)
        ));
        InventoryStock critical = mock(InventoryStock.class);
        when(critical.getVariantId()).thenReturn(variantId);
        when(critical.classifyHealth()).thenReturn(StockHealthStatus.CRITICAL);
        InventoryStock ok = mock(InventoryStock.class);
        when(ok.getVariantId()).thenReturn(variantId);
        when(ok.classifyHealth()).thenReturn(StockHealthStatus.OK);
        when(stockRepository.findByVariantIdIn(List.of(variantId))).thenReturn(List.of(critical, ok));

        CategoryStockHealthDto dto = service.getStockHealthByCategory().get(0);

        assertThat(dto.productCount()).isEqualTo(1);
        assertThat(dto.criticalCount()).isEqualTo(1);
        assertThat(dto.okCount()).isEqualTo(1);
        assertThat(dto.lowCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("should_sortCategoriesAlphabeticallyByName_when_multipleCategoriesReturned")
    void should_sortCategoriesAlphabeticallyByName_when_multipleCategoriesReturned() {
        UUID catA = UUID.randomUUID();
        UUID catB = UUID.randomUUID();
        when(productCatalogService.getAllCategoryProductVariantMappings()).thenReturn(List.of(
                new CategoryProductVariantRow(catB, "Zebra", null, null),
                new CategoryProductVariantRow(catA, "Apple", null, null)
        ));
        when(stockRepository.findByVariantIdIn(List.of())).thenReturn(List.of());

        List<CategoryStockHealthDto> result = service.getStockHealthByCategory();

        assertThat(result).extracting(CategoryStockHealthDto::categoryName).containsExactly("Apple", "Zebra");
    }
}
