package com.walmal.product.infrastructure;

import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.product.api.dto.request.CreateCategoryRequest;
import com.walmal.product.api.dto.request.CreateProductRequest;
import com.walmal.product.api.dto.request.CreateVariantRequest;
import com.walmal.product.api.dto.request.SetPriceRequest;
import com.walmal.product.api.dto.response.CategoryResponse;
import com.walmal.product.api.dto.response.ImageResponse;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductImageService;
import com.walmal.product.application.ProductManagementService;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import com.walmal.product.domain.ProductStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full integration test for the walmal-product module.
 *
 * <p>Uses a Testcontainers PostgreSQL instance. Flyway migrations V1, V2, V3 are applied
 * automatically via classpath:db/migration.</p>
 *
 * <p>Infrastructure beans (AuditService, CacheService, DomainEventPublisher, FileStorageService)
 * are provided by the inner {@link TestInfrastructureConfig}. AuditService writes real rows to
 * the audit_log table so assertions can verify the audit trail.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}
 * and is excluded from the default unit test phase.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ProductIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("walmal_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired ProductManagementService managementService;
    @Autowired ProductCatalogService catalogService;
    @Autowired ProductPricingService pricingService;
    @Autowired ProductImageService imageService;
    @Autowired JdbcTemplate jdbcTemplate;

    // ── Lifecycle: create category → product → variant → SKU lookup ──────────

    @Test
    void should_applyMigrationsAndCreateCategoryProductVariant_when_fullyInitialised() {
        CategoryResponse category = managementService.createCategory(
                new CreateCategoryRequest("Integration Test Category", "integration-test-cat", null));
        assertThat(category.id()).isNotNull();
        assertThat(category.slug()).isEqualTo("integration-test-cat");

        ProductDetailDto product = managementService.createProduct(
                new CreateProductRequest(category.id(), "Integration Widget",
                        "integration-widget", "A test widget", "TestBrand"),
                "test-admin");
        assertThat(product.productId()).isNotNull();
        assertThat(product.name()).isEqualTo("Integration Widget");

        VariantSummaryDto variant = managementService.createVariant(
                product.productId(),
                new CreateVariantRequest("INTTEST-SKU-001", "Small Blue Widget",
                        "BARCODE-001", null, new BigDecimal("29.99"), "USD"),
                "test-admin");
        assertThat(variant.sku()).isEqualTo("INTTEST-SKU-001");
        assertThat(variant.status()).isEqualTo(ProductStatus.ACTIVE);

        Optional<VariantSummaryDto> found = catalogService.findVariantBySku("INTTEST-SKU-001");
        assertThat(found).isPresent();
        assertThat(found.get().variantId()).isEqualTo(variant.variantId());
        assertThat(found.get().barcode()).isEqualTo("BARCODE-001");
    }

    // ── Price update → verify getCurrentPrice ────────────────────────────────

    @Test
    void should_returnCorrectPrice_when_priceIsSet() {
        CategoryResponse category = managementService.createCategory(
                new CreateCategoryRequest("Price Test Cat",
                        "price-test-cat-" + UUID.randomUUID(), null));
        ProductDetailDto product = managementService.createProduct(
                new CreateProductRequest(category.id(), "Price Test Product",
                        "price-test-product-" + UUID.randomUUID(), null, null),
                "test-admin");
        VariantSummaryDto variant = managementService.createVariant(
                product.productId(),
                new CreateVariantRequest("PRICE-SKU-" + UUID.randomUUID(), "Price Variant",
                        null, null, new BigDecimal("9.99"), "USD"),
                "test-admin");

        PriceDto initialPrice = pricingService.getPriceForVariant(variant.variantId());
        assertThat(initialPrice.amount()).isEqualByComparingTo(new BigDecimal("9.99"));
        assertThat(initialPrice.currency()).isEqualTo("USD");

        managementService.setPrice(variant.variantId(),
                new SetPriceRequest(new BigDecimal("19.99"), "USD"), "test-admin");

        PriceDto updatedPrice = pricingService.getPriceForVariant(variant.variantId());
        assertThat(updatedPrice.amount()).isEqualByComparingTo(new BigDecimal("19.99"));
    }

    // ── Deactivate variant → isVariantActive returns false ───────────────────

    @Test
    void should_returnFalse_when_variantDeactivated() {
        CategoryResponse category = managementService.createCategory(
                new CreateCategoryRequest("Deactivate Test Cat",
                        "deactivate-test-cat-" + UUID.randomUUID(), null));
        ProductDetailDto product = managementService.createProduct(
                new CreateProductRequest(category.id(), "Deactivate Test Product",
                        "deactivate-test-product-" + UUID.randomUUID(), null, null),
                "test-admin");
        VariantSummaryDto variant = managementService.createVariant(
                product.productId(),
                new CreateVariantRequest("DEACT-SKU-" + UUID.randomUUID(), "Deactivate Variant",
                        null, null, new BigDecimal("5.00"), "USD"),
                "test-admin");

        assertThat(catalogService.isVariantActive(variant.variantId())).isTrue();

        managementService.deactivateVariant(variant.variantId(), "test-admin");

        assertThat(catalogService.isVariantActive(variant.variantId())).isFalse();
    }

    // ── Audit log rows written for deactivateProduct and deactivateVariant ───

    @Test
    void should_writeAuditLogRows_when_deactivateProductAndVariant() {
        CategoryResponse category = managementService.createCategory(
                new CreateCategoryRequest("Audit Test Cat",
                        "audit-test-cat-" + UUID.randomUUID(), null));
        ProductDetailDto product = managementService.createProduct(
                new CreateProductRequest(category.id(), "Audit Test Product",
                        "audit-test-product-" + UUID.randomUUID(), null, null),
                "auditor");
        VariantSummaryDto variant = managementService.createVariant(
                product.productId(),
                new CreateVariantRequest("AUDIT-SKU-" + UUID.randomUUID(), "Audit Variant",
                        null, null, new BigDecimal("1.00"), "USD"),
                "auditor");

        // Deactivate variant — must write STATUS_CHANGE row for product_variants
        managementService.deactivateVariant(variant.variantId(), "auditor");

        Integer variantAuditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log " +
                "WHERE table_name = 'product_variants' " +
                "  AND record_id = ?::uuid " +
                "  AND action = 'STATUS_CHANGE' " +
                "  AND performed_by = 'auditor'",
                Integer.class, variant.variantId().toString());
        assertThat(variantAuditCount).as("audit_log row for deactivateVariant").isGreaterThanOrEqualTo(1);

        // Deactivate product — must write STATUS_CHANGE row for product_products
        managementService.deactivateProduct(product.productId(), "auditor");

        Integer productAuditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log " +
                "WHERE table_name = 'product_products' " +
                "  AND record_id = ?::uuid " +
                "  AND action = 'STATUS_CHANGE' " +
                "  AND performed_by = 'auditor'",
                Integer.class, product.productId().toString());
        assertThat(productAuditCount).as("audit_log row for deactivateProduct").isGreaterThanOrEqualTo(1);
    }

    // ── Set primary image when a primary already exists ──────────────────────
    // Regression: promoting a second image to primary while the product already
    // has one must NOT violate the partial unique index
    // idx_product_images_primary_per_product. The clear-old-primary UPDATE has to
    // reach the DB before the set-new-primary UPDATE; without the saveAndFlush in
    // clearExistingPrimary, Hibernate could flush "set new = TRUE" first and fail
    // with a duplicate-key error (HTTP 500 in the admin "Set Primary" action).
    @Test
    void should_promoteSecondImageToPrimary_when_productAlreadyHasAPrimary() {
        CategoryResponse category = managementService.createCategory(
                new CreateCategoryRequest("Primary Img Cat",
                        "primary-img-cat-" + UUID.randomUUID(), null));
        ProductDetailDto product = managementService.createProduct(
                new CreateProductRequest(category.id(), "Primary Img Product",
                        "primary-img-product-" + UUID.randomUUID(), null, null),
                "test-admin");
        UUID productId = product.productId();

        ImageResponse first = imageService.uploadImage(productId, null,
                new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}), "first.png",
                "image/png", 3L, "", true, "test-admin");
        ImageResponse second = imageService.uploadImage(productId, null,
                new java.io.ByteArrayInputStream(new byte[]{4, 5, 6}), "second.png",
                "image/png", 3L, "", false, "test-admin");
        assertThat(first.primary()).isTrue();
        assertThat(second.primary()).isFalse();

        // The operation that failed in production before the fix.
        imageService.setPrimaryImage(second.id(), "test-admin");

        Integer primaryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_images WHERE product_id = ?::uuid AND is_primary = TRUE",
                Integer.class, productId.toString());
        assertThat(primaryCount).as("exactly one primary image per product").isEqualTo(1);

        Boolean secondIsPrimary = jdbcTemplate.queryForObject(
                "SELECT is_primary FROM product_images WHERE id = ?::uuid",
                Boolean.class, second.id().toString());
        assertThat(secondIsPrimary).as("second image promoted to primary").isTrue();
    }

    // ── Test infrastructure configuration ────────────────────────────────────

    /**
     * Provides lightweight infrastructure beans for the integration test context.
     *
     * <p>walmal-infrastructure is not a compile dependency of walmal-product.
     * This configuration supplies the four interface implementations the product
     * service layer requires, without pulling in Redis, RabbitMQ, or MinIO.</p>
     *
     * <ul>
     *   <li>{@link AuditService} — writes real rows to audit_log via JdbcTemplate
     *       so integration tests can assert on the audit trail.</li>
     *   <li>{@link CacheService} — no-op; caching is not under test here.</li>
     *   <li>{@link DomainEventPublisher} — no-op; event publishing is unit-tested separately.</li>
     *   <li>{@link FileStorageService} — no-op; image storage is not exercised by these tests.</li>
     * </ul>
     */
    @TestConfiguration
    static class TestInfrastructureConfig {

        @Bean
        @Primary
        AuditService jdbcAuditService(JdbcTemplate jdbcTemplate) {
            return entry -> jdbcTemplate.update(
                    "INSERT INTO audit_log " +
                    "(id, table_name, record_id, action, old_value, new_value, performed_by, created_at) " +
                    "VALUES (gen_random_uuid(), ?, ?::uuid, ?, ?::jsonb, ?::jsonb, ?, NOW())",
                    entry.tableName(),
                    entry.recordId().toString(),
                    entry.action().name(),
                    entry.oldValue(),
                    entry.newValue(),
                    entry.performedBy());
        }

        @Bean
        @Primary
        CacheService noOpCacheService() {
            return new CacheService() {
                @Override public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }
                @Override public <T> void put(String key, T value) {}
                @Override public <T> void put(String key, T value, Duration ttl) {}
                @Override public void evict(String key) {}
                @Override public void evictByPrefix(String prefix) {}
                @Override public long increment(String key, Duration ttlOnCreate) { return 1L; }
            };
        }

        @Bean
        @Primary
        DomainEventPublisher noOpEventPublisher() {
            return new DomainEventPublisher() {
                @Override public void publish(DomainEvent event) {}
                @Override public void publish(DomainEvent event, String routingKey) {}
            };
        }

        @Bean
        @Primary
        FileStorageService noOpFileStorageService() {
            return new FileStorageService() {
                @Override public StoredFile upload(String bucket, String key, InputStream content,
                                                   String contentType, long size) {
                    return new StoredFile(key, bucket, contentType, size);
                }
                @Override public InputStream download(String bucket, String key) { return InputStream.nullInputStream(); }
                @Override public void delete(String bucket, String key) {}
                @Override public String getPresignedUrl(String bucket, String key) { return "http://test/" + key; }
            };
        }
    }
}
