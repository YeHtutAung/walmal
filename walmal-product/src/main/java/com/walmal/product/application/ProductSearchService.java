package com.walmal.product.application;

import com.walmal.product.application.dto.CategoryTreeDto;
import com.walmal.product.application.dto.ProductSummaryDto;
import com.walmal.product.domain.ProductStatus;
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
     *
     * <p>{@code status} is an opt-in filter: null = all statuses (the admin
     * products list depends on this exact behavior); non-null restricts the
     * results to that status (the storefront passes {@code ACTIVE}).</p>
     */
    Page<ProductSummaryDto> searchProducts(String query, ProductStatus status, Pageable pageable);

    /** Convenience overload: all statuses (equivalent to {@code status = null}). */
    default Page<ProductSummaryDto> searchProducts(String query, Pageable pageable) {
        return searchProducts(query, null, pageable);
    }

    /**
     * Returns products in the given category, optionally filtered by status
     * (null = all statuses — same opt-in contract as {@link #searchProducts}).
     * Returns a paginated result of product summaries.
     */
    Page<ProductSummaryDto> listByCategory(UUID categoryId, ProductStatus status, Pageable pageable);

    /** Convenience overload: all statuses (equivalent to {@code status = null}). */
    default Page<ProductSummaryDto> listByCategory(UUID categoryId, Pageable pageable) {
        return listByCategory(categoryId, null, pageable);
    }

    /**
     * Returns the full active category tree starting from root categories.
     * Cached at the implementation level — category tree changes rarely.
     */
    List<CategoryTreeDto> getCategoryTree();
}
