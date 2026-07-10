package com.walmal.product.application;

import com.walmal.product.api.dto.request.CreateCategoryRequest;
import com.walmal.product.api.dto.request.CreateProductRequest;
import com.walmal.product.api.dto.request.CreateVariantRequest;
import com.walmal.product.api.dto.request.SetPriceRequest;
import com.walmal.product.api.dto.request.UpdateCategoryRequest;
import com.walmal.product.api.dto.request.UpdateProductRequest;
import com.walmal.product.api.dto.response.CategoryResponse;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.VariantSummaryDto;

import java.util.UUID;

/**
 * Internal admin management interface — not exposed outside the walmal-product module.
 *
 * <p>SRP: This interface covers create/update/activate/deactivate of products and variants.
 * Pricing and image operations are in separate interfaces (ISP).</p>
 *
 * <p>All destructive operations (deactivate, setPrice) write to {@code audit_log} BEFORE
 * the DB mutation executes.</p>
 */
public interface ProductManagementService {

    /**
     * Creates a new product under the given category.
     *
     * @param request    product creation data
     * @param performedBy username of the authenticated user
     * @return the created product as a detail DTO
     */
    ProductDetailDto createProduct(CreateProductRequest request, String performedBy);

    /**
     * Updates product name, slug, description, brand, or category.
     * Publishes {@code ProductDetailsChangedEvent} after update.
     */
    ProductDetailDto updateProductDetails(UUID productId, UpdateProductRequest request, String performedBy);

    /**
     * Sets product status to INACTIVE. Writes audit_log before the DB mutation.
     * Publishes {@code ProductDeactivatedEvent}.
     */
    void deactivateProduct(UUID productId, String performedBy);

    /**
     * Sets product status back to ACTIVE. Writes audit_log before the DB mutation.
     */
    void activateProduct(UUID productId, String performedBy);

    /**
     * Creates a new variant under the given product, and creates an initial price row.
     * Publishes {@code ProductCreatedEvent}.
     */
    VariantSummaryDto createVariant(UUID productId, CreateVariantRequest request, String performedBy);

    /**
     * Sets variant status to INACTIVE. Writes audit_log before the DB mutation.
     * Evicts cached variant entries. Publishes {@code ProductDeactivatedEvent}.
     */
    void deactivateVariant(UUID variantId, String performedBy);

    /**
     * Sets variant status back to ACTIVE. Writes audit_log before the DB mutation.
     */
    void activateVariant(UUID variantId, String performedBy);

    /**
     * Updates (or creates, if first time) the price row for a variant.
     * Writes audit_log before the DB mutation. Evicts cached price entry.
     * Publishes {@code ProductPriceChangedEvent}.
     */
    PriceDto setPrice(UUID variantId, SetPriceRequest request, String performedBy);

    /**
     * Creates a new product category.
     */
    CategoryResponse createCategory(CreateCategoryRequest request);

    /**
     * Returns a single category by id.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if not found
     */
    CategoryResponse getCategory(UUID categoryId);

    /**
     * Updates a category's name and slug. Evicts the category tree cache.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if not found
     */
    CategoryResponse updateCategory(UUID categoryId, UpdateCategoryRequest request);

    /**
     * Returns all variants for the given product as summary DTOs.
     * Used by the controller layer to expose variant listing without coupling
     * to the Repository bean.
     */
    java.util.List<VariantSummaryDto> listVariants(UUID productId);
}
