package com.walmal.inventory.application.impl;

import com.walmal.inventory.api.dto.response.CategoryStockHealthDto;
import com.walmal.inventory.application.CategoryStockHealthService;
import com.walmal.inventory.domain.InventoryStock;
import com.walmal.inventory.domain.StockHealthStatus;
import com.walmal.inventory.infrastructure.InventoryStockRepository;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.dto.CategoryProductVariantRow;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link CategoryStockHealthService}.
 *
 * <p>Combines {@link ProductCatalogService#getAllCategoryProductVariantMappings()} (flat
 * category → product → variant rows, nullable productId/variantId for empty categories and
 * variant-less products) with {@link InventoryStockRepository#findByVariantIdIn} (per-variant
 * stock rows) to tally OK/LOW/CRITICAL counts per category.</p>
 */
@Service
public class CategoryStockHealthServiceImpl implements CategoryStockHealthService {

    private final ProductCatalogService productCatalogService;
    private final InventoryStockRepository stockRepository;

    public CategoryStockHealthServiceImpl(ProductCatalogService productCatalogService,
                                           InventoryStockRepository stockRepository) {
        this.productCatalogService = productCatalogService;
        this.stockRepository = stockRepository;
    }

    @Override
    public List<CategoryStockHealthDto> getStockHealthByCategory() {
        List<CategoryProductVariantRow> rows = productCatalogService.getAllCategoryProductVariantMappings();

        Map<UUID, List<CategoryProductVariantRow>> byCategory = rows.stream()
                .collect(Collectors.groupingBy(CategoryProductVariantRow::categoryId));

        List<UUID> variantIds = rows.stream()
                .map(CategoryProductVariantRow::variantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // Per-variant health counts, pre-tallied in one pass — avoids materializing a raw
        // List<StockHealthStatus> per variant just to re-filter it three times below.
        Map<UUID, Map<StockHealthStatus, Long>> healthCountsByVariant =
                stockRepository.findByVariantIdIn(variantIds).stream()
                        .collect(Collectors.groupingBy(InventoryStock::getVariantId,
                                Collectors.groupingBy(InventoryStock::classifyHealth, Collectors.counting())));

        return byCategory.entrySet().stream()
                .map(entry -> {
                    List<CategoryProductVariantRow> categoryRows = entry.getValue();
                    String categoryName = categoryRows.get(0).categoryName();
                    long productCount = categoryRows.stream()
                            .map(CategoryProductVariantRow::productId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .count();

                    long ok = 0, low = 0, critical = 0;
                    for (CategoryProductVariantRow row : categoryRows) {
                        UUID variantId = row.variantId();
                        if (variantId == null) continue;
                        Map<StockHealthStatus, Long> counts = healthCountsByVariant.get(variantId);
                        if (counts == null) continue;
                        ok += counts.getOrDefault(StockHealthStatus.OK, 0L);
                        low += counts.getOrDefault(StockHealthStatus.LOW, 0L);
                        critical += counts.getOrDefault(StockHealthStatus.CRITICAL, 0L);
                    }

                    return new CategoryStockHealthDto(entry.getKey(), categoryName, productCount, ok, low, critical);
                })
                .sorted(Comparator.comparing(CategoryStockHealthDto::categoryName))
                .toList();
    }
}
