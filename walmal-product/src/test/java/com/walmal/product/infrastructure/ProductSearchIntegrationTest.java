package com.walmal.product.infrastructure;

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
import com.walmal.product.application.ProductManagementService;
import com.walmal.product.application.ProductSearchService;
import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.ProductSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving {@link ProductRepository#searchByNameBrandSkuOrBarcode}
 * — the hand-written widened-search JPQL — behaves correctly against real Postgres,
 * end to end through {@link ProductSearchService#searchProducts}.
 *
 * <p>The unit tests for {@code ProductSearchServiceImpl} mock the repository, so
 * three failure modes are invisible to them and only provable here:</p>
 * <ul>
 *   <li><b>DISTINCT + explicit countQuery</b> — a product with two variants matching
 *       the same query must appear once, and {@code totalElements} must be 1
 *       (Spring Data's derived count over the join would over-count).</li>
 *   <li><b>{@code ESCAPE '\'}</b> — a query containing {@code _} must match only the
 *       literal underscore, not act as a single-character wildcard.</li>
 *   <li><b>Null-safety</b> — {@code lower(v.barcode)} over null barcodes must be
 *       simply non-matching, not an error.</li>
 * </ul>
 *
 * <p>Same Testcontainers setup pattern as {@link ProductIntegrationTest}.
 * Tagged {@code "integration"} so it runs only with {@code -DexcludedGroups=}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ProductSearchIntegrationTest {

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

    @Autowired ProductSearchService searchService;
    @Autowired ProductManagementService managementService;
    @Autowired JdbcTemplate jdbcTemplate;

    private ProductDetailDto productA;
    private ProductDetailDto productB;
    private ProductDetailDto productC;

    /**
     * Wipes and reseeds the catalog before every test so each assertion runs
     * against exactly the three known products (the {@code q=""} list-all case
     * asserts {@code totalElements == 3}, so no stray rows may survive).
     * {@code product_variants}, {@code product_prices}, and {@code product_images}
     * cascade from {@code product_products}.
     */
    @BeforeEach
    void reseedCatalog() {
        jdbcTemplate.update("DELETE FROM product_products");
        jdbcTemplate.update("DELETE FROM product_categories");

        CategoryResponse category = managementService.createCategory(
                new CreateCategoryRequest("Search Test Category", "search-test-cat", null));

        // Product A: two variants whose SKUs both contain "zed" — the DISTINCT /
        // countQuery case — plus a barcode and distinctive name/brand.
        productA = managementService.createProduct(
                new CreateProductRequest(category.id(), "Alpha Widget",
                        "alpha-widget", "Search test product A", "Acme"),
                "test-admin");
        managementService.createVariant(productA.productId(),
                new CreateVariantRequest("ZED-001", "Alpha Variant One",
                        "9990001", null, new BigDecimal("10.00"), "USD"),
                "test-admin");
        managementService.createVariant(productA.productId(),
                new CreateVariantRequest("ZED-002", "Alpha Variant Two",
                        null, null, new BigDecimal("12.00"), "USD"),
                "test-admin");

        // Product B: unrelated SKU/barcode, plus the adversarial "ABXC1" SKU that
        // WOULD match the query "ab_c1" if `_` acted as a single-char wildcard.
        productB = managementService.createProduct(
                new CreateProductRequest(category.id(), "Beta Gadget",
                        "beta-gadget", "Search test product B", "Bolt"),
                "test-admin");
        managementService.createVariant(productB.productId(),
                new CreateVariantRequest("QQQ-100", "Beta Variant",
                        "8880002", null, new BigDecimal("20.00"), "USD"),
                "test-admin");
        managementService.createVariant(productB.productId(),
                new CreateVariantRequest("ABXC1", "Beta Wildcard Decoy",
                        null, null, new BigDecimal("21.00"), "USD"),
                "test-admin");

        // Product C: the literal-underscore SKU targeted by the ESCAPE case.
        productC = managementService.createProduct(
                new CreateProductRequest(category.id(), "Gamma Thing",
                        "gamma-thing", "Search test product C", "Gamma Co"),
                "test-admin");
        managementService.createVariant(productC.productId(),
                new CreateVariantRequest("AB_C1", "Gamma Variant",
                        null, null, new BigDecimal("30.00"), "USD"),
                "test-admin");
    }

    // ── DISTINCT + explicit countQuery ────────────────────────────────────────

    @Test
    void should_returnOneRowAndTotalElementsOne_when_twoVariantsOfSameProductMatch() {
        Page<ProductSummaryDto> page = searchService.searchProducts("zed", PageRequest.of(0, 10));

        // Both ZED-001 and ZED-002 belong to Product A: DISTINCT must collapse the
        // two joined rows to one result row...
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).productId()).isEqualTo(productA.productId());

        // ...and totalElements comes from the explicit COUNT(DISTINCT p) countQuery,
        // not the row list — a derived count over the join would report 2 here.
        assertThat(page.getTotalElements()).isEqualTo(1);

        // Force the countQuery to actually execute: with pageSize 10 and 1 result,
        // Spring Data derives totalElements from the row-list size and skips the
        // count query entirely (PageableExecutionUtils optimization). A FULL first
        // page (size 1) makes it run COUNT(DISTINCT p) for real — a count that
        // failed to dedupe the two joined variant rows would report 2 here.
        Page<ProductSummaryDto> fullFirstPage =
                searchService.searchProducts("zed", PageRequest.of(0, 1));
        assertThat(fullFirstPage.getContent()).hasSize(1);
        assertThat(fullFirstPage.getTotalElements()).isEqualTo(1);
        assertThat(fullFirstPage.getTotalPages()).isEqualTo(1);
    }

    // ── Barcode-only matches ──────────────────────────────────────────────────

    @Test
    void should_findProductByVariantBarcode_when_queryMatchesNoOtherField() {
        Page<ProductSummaryDto> pageA = searchService.searchProducts("9990001", PageRequest.of(0, 10));
        assertThat(pageA.getContent())
                .extracting(ProductSummaryDto::productId)
                .containsExactly(productA.productId());

        Page<ProductSummaryDto> pageB = searchService.searchProducts("8880002", PageRequest.of(0, 10));
        assertThat(pageB.getContent())
                .extracting(ProductSummaryDto::productId)
                .containsExactly(productB.productId());
    }

    // ── Name and brand regressions ────────────────────────────────────────────

    @Test
    void should_findProductByName_when_queryMatchesName() {
        Page<ProductSummaryDto> page = searchService.searchProducts("alpha", PageRequest.of(0, 10));
        assertThat(page.getContent())
                .extracting(ProductSummaryDto::productId)
                .containsExactly(productA.productId());
    }

    @Test
    void should_findProductByBrand_when_queryMatchesBrand() {
        Page<ProductSummaryDto> page = searchService.searchProducts("acme", PageRequest.of(0, 10));
        assertThat(page.getContent())
                .extracting(ProductSummaryDto::productId)
                .containsExactly(productA.productId());
    }

    // ── ESCAPE '\' end-to-end ─────────────────────────────────────────────────

    @Test
    void should_matchLiteralUnderscoreOnly_when_queryContainsUnderscore() {
        Page<ProductSummaryDto> page = searchService.searchProducts("ab_c1", PageRequest.of(0, 10));

        List<java.util.UUID> matchedIds = page.getContent().stream()
                .map(ProductSummaryDto::productId)
                .toList();

        // Only Product C's literal "AB_C1" SKU matches the escaped pattern.
        assertThat(matchedIds).containsExactly(productC.productId());

        // Explicit negative: Product B's "ABXC1" SKU would match "ab_c1" if the
        // underscore acted as a single-character wildcard — the ESCAPE '\' clause
        // must exclude it against real Postgres.
        assertThat(matchedIds).doesNotContain(productB.productId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    // ── Empty query = list-all path preserved ─────────────────────────────────

    @Test
    void should_listAllProducts_when_queryIsEmpty() {
        Page<ProductSummaryDto> page = searchService.searchProducts("", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(ProductSummaryDto::productId)
                .containsExactlyInAnyOrder(
                        productA.productId(), productB.productId(), productC.productId());
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    // ── Test infrastructure configuration ────────────────────────────────────

    /**
     * Same shape as {@link ProductIntegrationTest.TestInfrastructureConfig}:
     * walmal-infrastructure is not a compile dependency of walmal-product, so the
     * four infrastructure interfaces the product service layer requires are stubbed
     * here without pulling in Redis, RabbitMQ, or MinIO.
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
