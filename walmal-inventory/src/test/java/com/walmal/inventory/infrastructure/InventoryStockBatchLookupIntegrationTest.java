package com.walmal.inventory.infrastructure;

import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.inventory.domain.InventoryLocation;
import com.walmal.inventory.domain.InventoryStock;
import com.walmal.inventory.domain.StockHealthStatus;
import com.walmal.product.application.ProductCatalogService;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving {@code InventoryStockRepository.findByVariantIdIn(...)} against a
 * real Postgres instance — specifically that it returns exactly the requested variant IDs'
 * stock rows and correctly EXCLUDES rows for variant IDs that were seeded but not requested
 * (proving the {@code IN} filter actually filters, not just "returns everything").
 *
 * <p>Seed values are chosen using the known {@link InventoryStock#classifyHealth()} threshold
 * math (CRITICAL at/below threshold, LOW up to 2x threshold, OK above that) so the test also
 * confirms the seeded rows land in the intended OK/LOW/CRITICAL bands.</p>
 *
 * <p>Uses a Testcontainers PostgreSQL instance, same style as {@link InventoryIntegrationTest}.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class InventoryStockBatchLookupIntegrationTest {

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

    @Autowired InventoryLocationRepository locationRepo;
    @Autowired InventoryStockRepository stockRepo;

    // Seed location ID from V4 migration (same constant InventoryIntegrationTest uses).
    private static final UUID MAIN_WAREHOUSE_ID =
            UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private static final int THRESHOLD = 10;

    // ── findByVariantIdIn returns the requested subset, excludes the rest ────

    @Test
    void should_returnOnlyRequestedVariantRows_when_findingByVariantIdIn() {
        InventoryLocation mainWarehouse = locationRepo.findById(MAIN_WAREHOUSE_ID)
                .orElseThrow(() -> new IllegalStateException("Main Warehouse seed data missing"));

        UUID okVariantId = UUID.randomUUID();
        UUID lowVariantId = UUID.randomUUID();
        UUID criticalVariantId = UUID.randomUUID();
        UUID excludedVariantId = UUID.randomUUID();

        // OK: availableQuantity=25 > threshold*2(=20) → OK.
        InventoryStock okStock = new InventoryStock(okVariantId, mainWarehouse, 25, THRESHOLD);
        // LOW: 10 < availableQuantity=15 <= threshold*2(=20) → LOW.
        InventoryStock lowStock = new InventoryStock(lowVariantId, mainWarehouse, 15, THRESHOLD);
        // CRITICAL: availableQuantity=5 <= threshold(=10) → CRITICAL.
        InventoryStock criticalStock = new InventoryStock(criticalVariantId, mainWarehouse, 5, THRESHOLD);
        // Seeded but deliberately NOT requested below — must be excluded from the result.
        InventoryStock excludedStock = new InventoryStock(excludedVariantId, mainWarehouse, 999, THRESHOLD);

        // Confirm the seed values actually land in the intended bands before relying on them,
        // per the task requirement not to just eyeball the threshold math.
        assertThat(okStock.classifyHealth()).isEqualTo(StockHealthStatus.OK);
        assertThat(lowStock.classifyHealth()).isEqualTo(StockHealthStatus.LOW);
        assertThat(criticalStock.classifyHealth()).isEqualTo(StockHealthStatus.CRITICAL);

        stockRepo.saveAll(List.of(okStock, lowStock, criticalStock, excludedStock));

        // Request only 3 of the 4 seeded variant IDs — proves the IN filter actually filters.
        List<UUID> requestedIds = List.of(okVariantId, lowVariantId, criticalVariantId);

        List<InventoryStock> result = stockRepo.findByVariantIdIn(requestedIds);

        Map<UUID, InventoryStock> byVariantId = result.stream()
                .collect(Collectors.toMap(InventoryStock::getVariantId, s -> s));

        assertThat(byVariantId.keySet())
                .as("result must contain exactly the requested variant IDs, no more, no less")
                .containsExactlyInAnyOrder(okVariantId, lowVariantId, criticalVariantId);
        assertThat(byVariantId)
                .as("excluded (seeded but unrequested) variant ID must not appear in the result")
                .doesNotContainKey(excludedVariantId);

        assertThat(byVariantId.get(okVariantId).getAvailableQuantity()).isEqualTo(25);
        assertThat(byVariantId.get(okVariantId).classifyHealth()).isEqualTo(StockHealthStatus.OK);

        assertThat(byVariantId.get(lowVariantId).getAvailableQuantity()).isEqualTo(15);
        assertThat(byVariantId.get(lowVariantId).classifyHealth()).isEqualTo(StockHealthStatus.LOW);

        assertThat(byVariantId.get(criticalVariantId).getAvailableQuantity()).isEqualTo(5);
        assertThat(byVariantId.get(criticalVariantId).classifyHealth()).isEqualTo(StockHealthStatus.CRITICAL);
    }

    // ── Test infrastructure configuration ────────────────────────────────────

    /**
     * Provides lightweight infrastructure beans for the integration test context, mirroring
     * {@link InventoryIntegrationTest.TestInfrastructureConfig}.
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

        @Bean
        @Primary
        ProductCatalogService noOpProductCatalogService() {
            return new ProductCatalogService() {
                @Override public Optional<com.walmal.product.application.dto.VariantSummaryDto> findVariantBySku(String sku) { return Optional.empty(); }
                @Override public com.walmal.product.application.dto.ProductDetailDto getProductDetails(UUID productId) { return null; }
                @Override public boolean isVariantActive(UUID variantId) { return true; }
                @Override public Optional<com.walmal.product.application.dto.VariantSummaryDto> findVariantById(UUID variantId) { return Optional.empty(); }
                @Override public List<com.walmal.product.application.dto.CategoryProductVariantRow> getAllCategoryProductVariantMappings() { return List.of(); }
            };
        }

        @Bean
        @Primary
        DistributedLockService noOpDistributedLockService() {
            return new DistributedLockService() {
                @Override public boolean tryLock(String key, Duration timeout) { return true; }
                @Override public void unlock(String key) {}
                @Override public <T> T executeWithLock(String key, Duration timeout, Supplier<T> action) { return action.get(); }
            };
        }
    }
}
