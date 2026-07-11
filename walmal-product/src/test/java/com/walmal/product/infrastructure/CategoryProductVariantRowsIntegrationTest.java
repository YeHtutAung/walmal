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
import com.walmal.product.api.dto.response.CategoryResponse;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductManagementService;
import com.walmal.product.application.dto.CategoryProductVariantRow;
import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.VariantSummaryDto;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving {@code CategoryRepository.findCategoryProductVariantRows()}'s LEFT
 * JOIN behaves correctly against a real Postgres instance — specifically that it preserves
 * categories with zero products and products with zero variants, rather than dropping them the
 * way an INNER JOIN would.
 *
 * <p>Uses a Testcontainers PostgreSQL instance, same style as {@link ProductIntegrationTest}.
 * Exercised via {@link ProductCatalogService#getAllCategoryProductVariantMappings()}, which is a
 * direct pass-through to {@code CategoryRepository.findCategoryProductVariantRows()} — see
 * {@code ProductCatalogServiceImpl}.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CategoryProductVariantRowsIntegrationTest {

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

    // ── LEFT JOIN preserves zero-variant products and zero-product categories ─

    @Test
    void should_includeZeroVariantProductsAndZeroProductCategories_when_findingCategoryProductVariantRows() {
        String suffix = UUID.randomUUID().toString();

        // Category A: has one product with one variant (normal case).
        CategoryResponse categoryWithVariant = managementService.createCategory(
                new CreateCategoryRequest("LeftJoin Cat With Variant", "ljcat-variant-" + suffix, null));
        ProductDetailDto productWithVariant = managementService.createProduct(
                new CreateProductRequest(categoryWithVariant.id(), "LeftJoin Product With Variant",
                        "ljprod-variant-" + suffix, null, null),
                "test-admin");
        VariantSummaryDto variant = managementService.createVariant(
                productWithVariant.productId(),
                new CreateVariantRequest("LJ-SKU-" + suffix, "LeftJoin Variant",
                        null, null, new BigDecimal("9.99"), "USD"),
                "test-admin");

        // Category B: has one product with ZERO variants — proves the LEFT JOIN on
        // p.variants doesn't drop the product row.
        CategoryResponse categoryWithZeroVariantProduct = managementService.createCategory(
                new CreateCategoryRequest("LeftJoin Cat Zero-Variant Product",
                        "ljcat-zerovariant-" + suffix, null));
        ProductDetailDto zeroVariantProduct = managementService.createProduct(
                new CreateProductRequest(categoryWithZeroVariantProduct.id(),
                        "LeftJoin Zero Variant Product", "ljprod-zerovariant-" + suffix, null, null),
                "test-admin");

        // Category C: ZERO products — proves the LEFT JOIN on Product doesn't drop the category.
        CategoryResponse categoryWithZeroProducts = managementService.createCategory(
                new CreateCategoryRequest("LeftJoin Cat Zero Products",
                        "ljcat-zeroproducts-" + suffix, null));

        List<CategoryProductVariantRow> rows = catalogService.getAllCategoryProductVariantMappings();

        // Category A: exactly one row, fully populated (category, product, variant all non-null).
        List<CategoryProductVariantRow> rowsForA = rows.stream()
                .filter(r -> r.categoryId().equals(categoryWithVariant.id()))
                .toList();
        assertThat(rowsForA).hasSize(1);
        assertThat(rowsForA.get(0).categoryName()).isEqualTo("LeftJoin Cat With Variant");
        assertThat(rowsForA.get(0).productId()).isEqualTo(productWithVariant.productId());
        assertThat(rowsForA.get(0).variantId()).isEqualTo(variant.variantId());

        // Category B: exactly one row — product present, variant null (LEFT JOIN on variants).
        List<CategoryProductVariantRow> rowsForB = rows.stream()
                .filter(r -> r.categoryId().equals(categoryWithZeroVariantProduct.id()))
                .toList();
        assertThat(rowsForB)
                .as("zero-variant product must still appear via LEFT JOIN, not be dropped")
                .hasSize(1);
        assertThat(rowsForB.get(0).productId()).isEqualTo(zeroVariantProduct.productId());
        assertThat(rowsForB.get(0).variantId()).isNull();

        // Category C: exactly one row — product AND variant null (LEFT JOIN on Product).
        List<CategoryProductVariantRow> rowsForC = rows.stream()
                .filter(r -> r.categoryId().equals(categoryWithZeroProducts.id()))
                .toList();
        assertThat(rowsForC)
                .as("zero-product category must still appear via LEFT JOIN, not be dropped")
                .hasSize(1);
        assertThat(rowsForC.get(0).categoryName()).isEqualTo("LeftJoin Cat Zero Products");
        assertThat(rowsForC.get(0).productId()).isNull();
        assertThat(rowsForC.get(0).variantId()).isNull();
    }

    // ── Test infrastructure configuration ────────────────────────────────────

    /**
     * Provides lightweight infrastructure beans for the integration test context, mirroring
     * {@link ProductIntegrationTest.TestInfrastructureConfig}.
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
