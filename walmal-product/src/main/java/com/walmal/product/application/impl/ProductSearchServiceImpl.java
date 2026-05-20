package com.walmal.product.application.impl;

import com.walmal.common.cache.CacheService;
import com.walmal.product.application.ProductSearchService;
import com.walmal.product.application.dto.CategoryTreeDto;
import com.walmal.product.application.dto.ProductSummaryDto;
import com.walmal.product.domain.Category;
import com.walmal.product.domain.Product;
import com.walmal.product.infrastructure.CategoryRepository;
import com.walmal.product.infrastructure.ProductPriceRepository;
import com.walmal.product.infrastructure.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ProductSearchService}.
 *
 * <p>DIP: depends on {@link ProductRepository}, {@link CategoryRepository},
 * {@link ProductPriceRepository}, and {@link CacheService} interfaces only.</p>
 *
 * <p>MVP search: ILIKE %query% on name and brand columns.
 * A full-text search engine is out of scope per CLAUDE.md.</p>
 */
@Service
@Transactional(readOnly = true)
public class ProductSearchServiceImpl implements ProductSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchServiceImpl.class);

    static final String CATEGORY_TREE_KEY = "product:category:tree";
    static final Duration CATEGORY_TREE_TTL = Duration.ofMinutes(30);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductPriceRepository priceRepository;
    private final CacheService cacheService;

    public ProductSearchServiceImpl(ProductRepository productRepository,
                                    CategoryRepository categoryRepository,
                                    ProductPriceRepository priceRepository,
                                    CacheService cacheService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.priceRepository = priceRepository;
        this.cacheService = cacheService;
    }

    @Override
    public Page<ProductSummaryDto> searchProducts(String query, Pageable pageable) {
        return productRepository
                .findByNameContainingIgnoreCaseOrBrandContainingIgnoreCase(query, query, pageable)
                .map(this::toProductSummaryDto);
    }

    @Override
    public Page<ProductSummaryDto> listByCategory(UUID categoryId, Pageable pageable) {
        return productRepository.findByCategoryId(categoryId, pageable)
                .map(this::toProductSummaryDto);
    }

    @Override
    public List<CategoryTreeDto> getCategoryTree() {
        Optional<List> cached = cacheService.get(CATEGORY_TREE_KEY, List.class);
        if (cached.isPresent()) {
            log.debug("Cache hit for category tree");
            // Safe unchecked cast — we always store List<CategoryTreeDto>
            @SuppressWarnings("unchecked")
            List<CategoryTreeDto> cachedTree = (List<CategoryTreeDto>) cached.get();
            return cachedTree;
        }

        List<Category> roots = categoryRepository.findByParentIsNull();
        List<CategoryTreeDto> tree = roots.stream()
                .map(this::toCategoryTreeDto)
                .collect(Collectors.toList());

        cacheService.put(CATEGORY_TREE_KEY, tree, CATEGORY_TREE_TTL);
        return tree;
    }

    /** Evicts the category tree cache — called after any category mutation. */
    public void evictCategoryTreeCache() {
        cacheService.evict(CATEGORY_TREE_KEY);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private ProductSummaryDto toProductSummaryDto(Product p) {
        // Determine lowest price across all variants — simplified for MVP
        BigDecimal lowestPrice = null;
        String currency = "USD";
        if (!p.getVariants().isEmpty()) {
            for (var variant : p.getVariants()) {
                var priceOpt = priceRepository.findByVariantId(variant.getId());
                if (priceOpt.isPresent()) {
                    BigDecimal amount = priceOpt.get().getAmount();
                    if (lowestPrice == null || amount.compareTo(lowestPrice) < 0) {
                        lowestPrice = amount;
                        currency = priceOpt.get().getCurrency();
                    }
                }
            }
        }

        return new ProductSummaryDto(
                p.getId(),
                p.getName(),
                p.getSlug(),
                p.getBrand(),
                null,       // primaryImageUrl: resolved at controller layer if needed
                lowestPrice,
                currency
        );
    }

    private CategoryTreeDto toCategoryTreeDto(Category category) {
        List<CategoryTreeDto> childDtos = category.getChildren().stream()
                .map(this::toCategoryTreeDto)
                .collect(Collectors.toList());
        return new CategoryTreeDto(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.isActive(),
                childDtos
        );
    }
}
