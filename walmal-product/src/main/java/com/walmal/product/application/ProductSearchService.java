package com.walmal.product.application;

import com.walmal.product.application.dto.CategoryTreeDto;
import com.walmal.product.application.dto.ProductSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Cross-module service interface — consumed by the API Gateway layer.
 *
 * <p>ISP: Search and browse are read-only, high-traffic operations used only by the API Gateway
 * (web storefront, mobile). Order and POS never need free-text search or browse.</p>
 */
public interface ProductSearchService {

    /**
     * Full-text search across product name and brand.
     * MVP implementation: {@code ILIKE %query%} on name and brand columns.
     * Returns a paginated result of product summaries.
     */
    Page<ProductSummaryDto> searchProducts(String query, Pageable pageable);

    /**
     * Returns all ACTIVE products in the given category.
     * Returns a paginated result of product summaries.
     */
    Page<ProductSummaryDto> listByCategory(UUID categoryId, Pageable pageable);

    /**
     * Returns the full active category tree starting from root categories.
     * Cached at the implementation level — category tree changes rarely.
     */
    List<CategoryTreeDto> getCategoryTree();
}
