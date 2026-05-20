package com.walmal.product.application.impl;

import com.walmal.common.cache.CacheService;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import com.walmal.product.domain.Product;
import com.walmal.product.domain.ProductStatus;
import com.walmal.product.domain.ProductVariant;
import com.walmal.product.infrastructure.ProductRepository;
import com.walmal.product.infrastructure.ProductVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of {@link ProductCatalogService}.
 *
 * <p>DIP: depends on {@link ProductVariantRepository}, {@link ProductRepository}, and
 * {@link CacheService} interfaces only. No Redis, Hibernate, or Spring Data internals
 * are visible outside this method boundary.</p>
 *
 * <p>Cache-aside pattern: read from cache first; on miss query DB and populate cache.</p>
 */
@Service
@Transactional(readOnly = true)
public class ProductCatalogServiceImpl implements ProductCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogServiceImpl.class);

    static final String SKU_CACHE_PREFIX = "product:variant:sku:";
    static final String VARIANT_CACHE_PREFIX = "product:variant:";
    static final Duration VARIANT_TTL = Duration.ofMinutes(5);

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final CacheService cacheService;

    public ProductCatalogServiceImpl(ProductVariantRepository variantRepository,
                                     ProductRepository productRepository,
                                     CacheService cacheService) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.cacheService = cacheService;
    }

    @Override
    public Optional<VariantSummaryDto> findVariantBySku(String sku) {
        String cacheKey = SKU_CACHE_PREFIX + sku;
        Optional<VariantSummaryDto> cached = cacheService.get(cacheKey, VariantSummaryDto.class);
        if (cached.isPresent()) {
            log.debug("Cache hit for variant SKU: {}", sku);
            return cached;
        }

        Optional<ProductVariant> variant = variantRepository.findBySku(sku);
        Optional<VariantSummaryDto> result = variant.map(v -> toVariantSummaryDto(v, v.getProduct()));

        result.ifPresent(dto -> cacheService.put(cacheKey, dto, VARIANT_TTL));
        return result;
    }

    @Override
    public ProductDetailDto getProductDetails(UUID productId) {
        String cacheKey = "product:detail:" + productId;
        Optional<ProductDetailDto> cached = cacheService.get(cacheKey, ProductDetailDto.class);
        if (cached.isPresent()) {
            log.debug("Cache hit for product detail: {}", productId);
            return cached.get();
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        ProductDetailDto dto = toProductDetailDto(product);
        cacheService.put(cacheKey, dto, Duration.ofMinutes(10));
        return dto;
    }

    @Override
    public boolean isVariantActive(UUID variantId) {
        String cacheKey = VARIANT_CACHE_PREFIX + variantId;
        Optional<VariantSummaryDto> cached = cacheService.get(cacheKey, VariantSummaryDto.class);
        if (cached.isPresent()) {
            return cached.get().status() == ProductStatus.ACTIVE;
        }

        return variantRepository.findByIdWithProduct(variantId)
                .map(v -> v.getStatus() == ProductStatus.ACTIVE
                        && v.getProduct().getStatus() == ProductStatus.ACTIVE)
                .orElse(false);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private VariantSummaryDto toVariantSummaryDto(ProductVariant v, Product p) {
        // Extract color and size from attributes map for convenience
        String color = null;
        String size = null;
        if (v.getAttributes() != null) {
            Object colorVal = v.getAttributes().get("color");
            Object sizeVal = v.getAttributes().get("size");
            color = colorVal != null ? colorVal.toString() : null;
            size = sizeVal != null ? sizeVal.toString() : null;
        }
        return new VariantSummaryDto(
                v.getId(),
                p.getId(),
                v.getSku(),
                v.getBarcode(),
                p.getName(),
                color,
                size,
                v.getStatus()
        );
    }

    private ProductDetailDto toProductDetailDto(Product p) {
        String categoryName = (p.getCategory() != null) ? p.getCategory().getName() : null;
        return new ProductDetailDto(
                p.getId(),
                p.getName(),
                p.getSlug(),
                p.getBrand(),
                p.getDescription(),
                p.getStatus().name(),
                categoryName
        );
    }
}
