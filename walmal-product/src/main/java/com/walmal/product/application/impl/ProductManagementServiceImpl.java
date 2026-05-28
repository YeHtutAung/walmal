package com.walmal.product.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.product.api.dto.request.CreateCategoryRequest;
import com.walmal.product.api.dto.request.CreateProductRequest;
import com.walmal.product.api.dto.request.CreateVariantRequest;
import com.walmal.product.api.dto.request.SetPriceRequest;
import com.walmal.product.api.dto.request.UpdateProductRequest;
import com.walmal.product.api.dto.response.CategoryResponse;
import com.walmal.product.application.ProductManagementService;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import com.walmal.product.domain.Category;
import com.walmal.product.domain.Product;
import com.walmal.product.domain.ProductPrice;
import com.walmal.product.domain.ProductStatus;
import com.walmal.product.domain.ProductVariant;
import com.walmal.product.domain.event.ProductCreatedEvent;
import com.walmal.product.domain.event.ProductDeactivatedEvent;
import com.walmal.product.domain.event.ProductDetailsChangedEvent;
import com.walmal.product.domain.event.ProductPriceChangedEvent;
import com.walmal.product.infrastructure.CategoryRepository;
import com.walmal.product.infrastructure.ProductPriceRepository;
import com.walmal.product.infrastructure.ProductRepository;
import java.util.stream.Collectors;
import com.walmal.product.infrastructure.ProductVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Implementation of {@link ProductManagementService}.
 *
 * <p>DIP: all infrastructure dependencies — audit, cache, events, persistence — are
 * accessed through interfaces only. No Spring Data, Redis, or RabbitMQ types are
 * referenced in this class.</p>
 *
 * <p>Audit rule: AuditService.log() is called BEFORE every destructive DB mutation.
 * This is verified by Mockito InOrder assertions in the unit test suite.</p>
 */
@Service
@Transactional
public class ProductManagementServiceImpl implements ProductManagementService {

    private static final Logger log = LoggerFactory.getLogger(ProductManagementServiceImpl.class);

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductPriceRepository priceRepository;
    private final CategoryRepository categoryRepository;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;
    private final CacheService cacheService;

    public ProductManagementServiceImpl(ProductRepository productRepository,
                                        ProductVariantRepository variantRepository,
                                        ProductPriceRepository priceRepository,
                                        CategoryRepository categoryRepository,
                                        AuditService auditService,
                                        DomainEventPublisher eventPublisher,
                                        CacheService cacheService) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.priceRepository = priceRepository;
        this.categoryRepository = categoryRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
    }

    // ── Product operations ────────────────────────────────────────────────────

    @Override
    public ProductDetailDto createProduct(CreateProductRequest request, String performedBy) {
        Category category = findCategoryOrThrow(request.categoryId());
        Product product = new Product(
                request.name(), request.slug(), request.description(),
                request.brand(), category);
        product = productRepository.save(product);
        log.info("Product created: {} by {}", product.getId(), performedBy);
        return toProductDetailDto(product);
    }

    @Override
    public ProductDetailDto updateProductDetails(UUID productId, UpdateProductRequest request,
                                                  String performedBy) {
        Product product = findProductOrThrow(productId);

        if (request.name() != null) product.setName(request.name());
        if (request.slug() != null) product.setSlug(request.slug());
        if (request.description() != null) product.setDescription(request.description());
        if (request.brand() != null) product.setBrand(request.brand());
        if (request.categoryId() != null) {
            Category category = findCategoryOrThrow(request.categoryId());
            product.setCategory(category);
        }

        product = productRepository.save(product);

        // Evict cache
        cacheService.evict("product:detail:" + productId);

        // Publish event
        eventPublisher.publish(
                new ProductDetailsChangedEvent(product.getId(), product.getName(),
                        product.getBrand(), product.getStatus().name()),
                "product.details.changed");

        return toProductDetailDto(product);
    }

    @Override
    public void deactivateProduct(UUID productId, String performedBy) {
        Product product = findProductOrThrow(productId);

        // AUDIT FIRST — before any DB mutation (architecture rule)
        auditService.log(new AuditEntry(
                "product_products", productId, AuditAction.STATUS_CHANGE,
                "{\"status\":\"ACTIVE\"}", "{\"status\":\"INACTIVE\"}", performedBy));

        product.deactivate();
        productRepository.save(product);

        // Evict cache
        cacheService.evict("product:detail:" + productId);

        // Publish event
        eventPublisher.publish(
                new ProductDeactivatedEvent(productId, "PRODUCT", null, productId, performedBy),
                "product.deactivated");

        log.info("Product deactivated: {} by {}", productId, performedBy);
    }

    @Override
    public void activateProduct(UUID productId, String performedBy) {
        Product product = findProductOrThrow(productId);

        // AUDIT FIRST
        auditService.log(new AuditEntry(
                "product_products", productId, AuditAction.STATUS_CHANGE,
                "{\"status\":\"INACTIVE\"}", "{\"status\":\"ACTIVE\"}", performedBy));

        product.activate();
        productRepository.save(product);

        cacheService.evict("product:detail:" + productId);
        log.info("Product activated: {} by {}", productId, performedBy);
    }

    // ── Variant operations ────────────────────────────────────────────────────

    @Override
    public VariantSummaryDto createVariant(UUID productId, CreateVariantRequest request,
                                           String performedBy) {
        Product product = findProductOrThrow(productId);

        ProductVariant variant = new ProductVariant(
                product, request.sku(), request.name(),
                request.barcode(), request.attributes());
        variant = variantRepository.save(variant);

        // Create initial price row
        ProductPrice price = new ProductPrice(
                variant,
                request.initialPrice(),
                request.currency() != null ? request.currency() : "USD",
                Instant.now());
        priceRepository.save(price);

        // Publish event
        eventPublisher.publish(
                new ProductCreatedEvent(variant.getId(), product.getId(),
                        variant.getSku(), product.getName()),
                "product.created");

        log.info("Variant created: {} (SKU: {}) by {}", variant.getId(), variant.getSku(), performedBy);
        return toVariantSummaryDto(variant, product);
    }

    @Override
    public void deactivateVariant(UUID variantId, String performedBy) {
        ProductVariant variant = findVariantOrThrow(variantId);

        // AUDIT FIRST
        auditService.log(new AuditEntry(
                "product_variants", variantId, AuditAction.STATUS_CHANGE,
                "{\"status\":\"ACTIVE\"}", "{\"status\":\"INACTIVE\"}", performedBy));

        variant.deactivate();
        variantRepository.save(variant);

        // Evict both cache keys for this variant
        cacheService.evict("product:variant:sku:" + variant.getSku());
        cacheService.evict("product:variant:" + variantId);

        // Publish event
        UUID productId = variant.getProduct().getId();
        eventPublisher.publish(
                new ProductDeactivatedEvent(variantId, "VARIANT", variant.getSku(),
                        productId, performedBy),
                "product.deactivated");

        log.info("Variant deactivated: {} by {}", variantId, performedBy);
    }

    @Override
    public void activateVariant(UUID variantId, String performedBy) {
        ProductVariant variant = findVariantOrThrow(variantId);

        // AUDIT FIRST
        auditService.log(new AuditEntry(
                "product_variants", variantId, AuditAction.STATUS_CHANGE,
                "{\"status\":\"INACTIVE\"}", "{\"status\":\"ACTIVE\"}", performedBy));

        variant.activate();
        variantRepository.save(variant);

        cacheService.evict("product:variant:sku:" + variant.getSku());
        cacheService.evict("product:variant:" + variantId);

        log.info("Variant activated: {} by {}", variantId, performedBy);
    }

    // ── Price operations ──────────────────────────────────────────────────────

    @Override
    public PriceDto setPrice(UUID variantId, SetPriceRequest request, String performedBy) {
        ProductVariant variant = findVariantOrThrow(variantId);

        ProductPrice price = priceRepository.findByVariantId(variantId).orElse(null);

        BigDecimal oldAmount = (price != null) ? price.getAmount() : BigDecimal.ZERO;
        String oldCurrency = (price != null) ? price.getCurrency() : "USD";

        // AUDIT FIRST — before any DB mutation
        auditService.log(new AuditEntry(
                "product_prices", variantId, AuditAction.UPDATE,
                String.format("{\"amount\":\"%s\",\"currency\":\"%s\"}", oldAmount, oldCurrency),
                String.format("{\"amount\":\"%s\",\"currency\":\"%s\"}",
                        request.amount(), request.currency()),
                performedBy));

        if (price == null) {
            price = new ProductPrice(variant, request.amount(), request.currency(), Instant.now());
        } else {
            price.setAmount(request.amount());
            price.setCurrency(request.currency());
            price.setEffectiveFrom(Instant.now());
        }
        price = priceRepository.save(price);

        // Evict price cache
        cacheService.evict("product:price:" + variantId);

        // Publish event
        eventPublisher.publish(
                new ProductPriceChangedEvent(variantId, variant.getProduct().getId(),
                        oldAmount, request.amount(), request.currency()),
                "product.price.changed");

        log.info("Price set for variant: {} amount: {} by {}", variantId, request.amount(), performedBy);
        return new PriceDto(variantId, price.getAmount(), price.getCurrency(), price.getEffectiveFrom());
    }

    // ── Category operations ───────────────────────────────────────────────────

    @Override
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        Category parent = null;
        if (request.parentId() != null) {
            parent = findCategoryOrThrow(request.parentId());
        }
        Category category = new Category(request.name(), request.slug(), parent);
        category = categoryRepository.save(category);

        // Evict category tree cache
        cacheService.evict("product:category:tree");

        log.info("Category created: {} (slug: {})", category.getId(), category.getSlug());
        return toCategoryResponse(category);
    }

    // ── Variant listing ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public java.util.List<VariantSummaryDto> listVariants(UUID productId) {
        return variantRepository.findByProductId(productId).stream()
                .map(v -> toVariantSummaryDto(v, v.getProduct()))
                .collect(java.util.stream.Collectors.toList());
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private ProductDetailDto toProductDetailDto(Product p) {
        String categoryName = (p.getCategory() != null) ? p.getCategory().getName() : null;
        return new ProductDetailDto(
                p.getId(), p.getName(), p.getSlug(), p.getBrand(),
                p.getDescription(), p.getStatus().name(), categoryName, null, null, null);
    }

    private VariantSummaryDto toVariantSummaryDto(ProductVariant v, Product p) {
        String color = null;
        String size = null;
        if (v.getAttributes() != null) {
            Object c = v.getAttributes().get("color");
            Object s = v.getAttributes().get("size");
            color = c != null ? c.toString() : null;
            size = s != null ? s.toString() : null;
        }
        return new VariantSummaryDto(
                v.getId(), p.getId(), v.getSku(), v.getBarcode(),
                p.getName(), color, size, v.getStatus());
    }

    private CategoryResponse toCategoryResponse(Category c) {
        UUID parentId = (c.getParent() != null) ? c.getParent().getId() : null;
        return new CategoryResponse(
                c.getId(), c.getName(), c.getSlug(), parentId,
                c.isActive(), c.getCreatedAt(), c.getUpdatedAt());
    }

    // ── Lookup helpers ────────────────────────────────────────────────────────

    private Product findProductOrThrow(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private ProductVariant findVariantOrThrow(UUID variantId) {
        return variantRepository.findByIdWithProduct(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId));
    }

    private Category findCategoryOrThrow(UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));
    }
}
