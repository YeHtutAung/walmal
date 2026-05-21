package com.walmal.product.application;

import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.VariantSummaryDto;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module service interface — consumed by Order and POS modules.
 *
 * <p>ISP: Order and POS need catalog lookups only. They do not need search or price mutation.
 * This interface exposes only what those consumers need.</p>
 *
 * <p>Architecture rule: Order and POS depend on this interface only. They never import
 * {@code ProductRepository} or any other infrastructure class from this module.</p>
 */
public interface ProductCatalogService {

    /**
     * Returns lightweight variant details by SKU for use during order creation.
     * Returns empty if the SKU does not exist or belongs to an inactive variant or product.
     * This is a hot path — results are cached at the implementation level.
     */
    Optional<VariantSummaryDto> findVariantBySku(String sku);

    /**
     * Returns product details for display in order confirmation and POS receipt.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if the product does not exist
     */
    ProductDetailDto getProductDetails(UUID productId);

    /**
     * Returns true if the variant exists and both the variant and its parent product
     * have status ACTIVE. Used by Order to reject orders for discontinued items.
     */
    boolean isVariantActive(UUID variantId);

    /**
     * Returns lightweight variant details by variant UUID for use during order creation.
     *
     * <p>Required by the Order module to snapshot product name and SKU at order creation time
     * when only the variantId is available (no SKU provided by the caller). Returns empty if
     * the variant does not exist or belongs to an inactive product.</p>
     *
     * <p>ISP note: added alongside {@link #findVariantBySku} to serve the Order module's
     * lookup-by-id use case without requiring the caller to supply a SKU upfront.</p>
     */
    Optional<VariantSummaryDto> findVariantById(UUID variantId);
}
