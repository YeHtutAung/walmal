package com.walmal.product.application;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.product.api.dto.request.CreateVariantRequest;
import com.walmal.product.api.dto.request.SetPriceRequest;
import com.walmal.product.application.impl.ProductManagementServiceImpl;
import com.walmal.product.domain.Category;
import com.walmal.product.domain.Product;
import com.walmal.product.domain.ProductPrice;
import com.walmal.product.domain.ProductStatus;
import com.walmal.product.domain.ProductVariant;
import com.walmal.product.domain.event.ProductCreatedEvent;
import com.walmal.product.domain.event.ProductDeactivatedEvent;
import com.walmal.product.domain.event.ProductPriceChangedEvent;
import com.walmal.product.infrastructure.CategoryRepository;
import com.walmal.product.infrastructure.ProductPriceRepository;
import com.walmal.product.infrastructure.ProductRepository;
import com.walmal.product.infrastructure.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductManagementServiceImpl}.
 *
 * <p>Key invariant verified: AuditService.log() is called BEFORE any DB mutation
 * (verified via Mockito InOrder).</p>
 */
@ExtendWith(MockitoExtension.class)
class ProductManagementServiceImplTest {

    @Mock ProductRepository productRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock ProductPriceRepository priceRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock AuditService auditService;
    @Mock DomainEventPublisher eventPublisher;
    @Mock CacheService cacheService;

    ProductManagementServiceImpl service;

    private UUID productId;
    private UUID variantId;

    @BeforeEach
    void setUp() {
        service = new ProductManagementServiceImpl(
                productRepository, variantRepository, priceRepository, categoryRepository,
                auditService, eventPublisher, cacheService);

        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private Product mockProduct() {
        Category category = mock(Category.class);
        lenient().when(category.getId()).thenReturn(UUID.randomUUID());
        lenient().when(category.getName()).thenReturn("Electronics");

        Product product = mock(Product.class);
        lenient().when(product.getId()).thenReturn(productId);
        lenient().when(product.getName()).thenReturn("Test Product");
        lenient().when(product.getSlug()).thenReturn("test-product");
        lenient().when(product.getBrand()).thenReturn("TestBrand");
        lenient().when(product.getStatus()).thenReturn(ProductStatus.ACTIVE);
        lenient().when(product.getCategory()).thenReturn(category);
        return product;
    }

    private ProductVariant mockVariant(Product product) {
        ProductVariant variant = mock(ProductVariant.class);
        lenient().when(variant.getId()).thenReturn(variantId);
        lenient().when(variant.getSku()).thenReturn("SKU-001");
        lenient().when(variant.getProduct()).thenReturn(product);
        lenient().when(variant.getStatus()).thenReturn(ProductStatus.ACTIVE);
        return variant;
    }

    // ── Deactivate product — audit BEFORE status update ───────────────────────

    @Test
    void should_callAuditBeforeStatusUpdate_when_deactivatingProduct() {
        Product product = mockProduct();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenReturn(product);

        service.deactivateProduct(productId, "admin");

        InOrder inOrder = inOrder(auditService, product);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(product).deactivate();
    }

    @Test
    void should_publishProductDeactivatedEvent_when_productDeactivated() {
        Product product = mockProduct();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenReturn(product);

        service.deactivateProduct(productId, "admin");

        verify(eventPublisher).publish(any(ProductDeactivatedEvent.class), eq("product.deactivated"));
    }

    @Test
    void should_evictCacheAfterDeactivation() {
        Product product = mockProduct();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenReturn(product);

        service.deactivateProduct(productId, "admin");

        verify(cacheService).evict("product:detail:" + productId);
    }

    // ── Deactivate variant — audit BEFORE status update ───────────────────────

    @Test
    void should_callAuditBeforeStatusUpdate_when_deactivatingVariant() {
        Product product = mockProduct();
        ProductVariant variant = mockVariant(product);
        when(variantRepository.findByIdWithProduct(variantId)).thenReturn(Optional.of(variant));
        when(variantRepository.save(any())).thenReturn(variant);

        service.deactivateVariant(variantId, "admin");

        InOrder inOrder = inOrder(auditService, variant);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(variant).deactivate();
    }

    // ── Activate product — audit BEFORE status update ────────────────────────

    @Test
    void should_callAuditBeforeStatusUpdate_when_activatingProduct() {
        Product product = mockProduct();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenReturn(product);

        service.activateProduct(productId, "admin");

        InOrder inOrder = inOrder(auditService, product);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(product).activate();
    }

    // ── Activate variant — audit BEFORE status update ─────────────────────────

    @Test
    void should_callAuditBeforeStatusUpdate_when_activatingVariant() {
        Product product = mockProduct();
        ProductVariant variant = mockVariant(product);
        when(variantRepository.findByIdWithProduct(variantId)).thenReturn(Optional.of(variant));
        when(variantRepository.save(any())).thenReturn(variant);

        service.activateVariant(variantId, "admin");

        InOrder inOrder = inOrder(auditService, variant);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(variant).activate();
    }

    // ── Set price — audit BEFORE price update ─────────────────────────────────

    @Test
    void should_callAuditBeforePriceUpdate_when_settingPrice() {
        Product product = mockProduct();
        ProductVariant variant = mockVariant(product);

        ProductPrice existingPrice = mock(ProductPrice.class);
        when(existingPrice.getAmount()).thenReturn(new BigDecimal("10.00"));
        when(existingPrice.getCurrency()).thenReturn("USD");
        when(existingPrice.getEffectiveFrom()).thenReturn(Instant.now());

        when(variantRepository.findByIdWithProduct(variantId)).thenReturn(Optional.of(variant));
        when(priceRepository.findByVariantId(variantId)).thenReturn(Optional.of(existingPrice));
        when(priceRepository.save(any())).thenReturn(existingPrice);

        SetPriceRequest request = new SetPriceRequest(new BigDecimal("25.00"), "USD");
        service.setPrice(variantId, request, "admin");

        InOrder inOrder = inOrder(auditService, priceRepository);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(priceRepository).save(any());
    }

    @Test
    void should_publishProductPriceChangedEvent_when_priceSet() {
        Product product = mockProduct();
        ProductVariant variant = mockVariant(product);

        ProductPrice existingPrice = mock(ProductPrice.class);
        when(existingPrice.getAmount()).thenReturn(new BigDecimal("10.00"));
        when(existingPrice.getCurrency()).thenReturn("USD");
        when(existingPrice.getEffectiveFrom()).thenReturn(Instant.now());

        when(variantRepository.findByIdWithProduct(variantId)).thenReturn(Optional.of(variant));
        when(priceRepository.findByVariantId(variantId)).thenReturn(Optional.of(existingPrice));
        when(priceRepository.save(any())).thenReturn(existingPrice);

        SetPriceRequest request = new SetPriceRequest(new BigDecimal("25.00"), "USD");
        service.setPrice(variantId, request, "admin");

        verify(eventPublisher).publish(any(ProductPriceChangedEvent.class), eq("product.price.changed"));
    }

    // ── Create variant — publishes ProductCreatedEvent ────────────────────────

    @Test
    void should_publishProductCreatedEvent_when_variantCreated() {
        Product product = mockProduct();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        ProductVariant savedVariant = new ProductVariant(product, "SKU-NEW", "New Variant", null, null);
        when(variantRepository.save(any())).thenReturn(savedVariant);

        ProductPrice savedPrice = new ProductPrice(savedVariant, new BigDecimal("19.99"), "USD", Instant.now());
        when(priceRepository.save(any())).thenReturn(savedPrice);

        CreateVariantRequest request = new CreateVariantRequest(
                "SKU-NEW", "New Variant", null, null,
                new BigDecimal("19.99"), "USD");

        service.createVariant(productId, request, "admin");

        verify(eventPublisher).publish(any(ProductCreatedEvent.class), eq("product.created"));
    }

    // ── Audit entry contains correct action ───────────────────────────────────

    @Test
    void should_logStatusChangeAction_when_deactivatingProduct() {
        Product product = mockProduct();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenReturn(product);

        service.deactivateProduct(productId, "admin");

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).log(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.STATUS_CHANGE);
        assertThat(captor.getValue().tableName()).isEqualTo("product_products");
        assertThat(captor.getValue().performedBy()).isEqualTo("admin");
    }
}
