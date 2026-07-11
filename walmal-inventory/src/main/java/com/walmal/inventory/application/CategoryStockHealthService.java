package com.walmal.inventory.application;

import com.walmal.inventory.api.dto.response.CategoryStockHealthDto;

import java.util.List;

/**
 * Orchestrates the category-level stock-health rollup by combining
 * {@code ProductCatalogService}'s category/product/variant mappings (walmal-product) with
 * per-variant stock-health classification (walmal-inventory).
 */
public interface CategoryStockHealthService {

    /**
     * Returns one {@link CategoryStockHealthDto} per category, sorted alphabetically by
     * category name, including categories with zero products.
     */
    List<CategoryStockHealthDto> getStockHealthByCategory();
}
