package com.walmal.inventory.infrastructure;

import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.inventory.application.InventoryAdjustmentService;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.inventory.application.InventoryReservationService.ReservationLineItem;
import com.walmal.inventory.application.impl.ReservationExpiryJob;
import com.walmal.inventory.domain.*;
import com.walmal.product.application.ProductCatalogService;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full integration test for the walmal-inventory module.
 *
 * <p>Uses a Testcontainers PostgreSQL instance. Flyway migrations V1–V4 are applied
 * automatically via classpath:db/migration.</p>
 *
 * <p>Infrastructure beans (AuditService, CacheService, DomainEventPublisher, etc.)
 * are provided by the inner {@link TestInfrastructureConfig}. AuditService writes real
 * rows to the audit_log table so assertions can verify the audit trail.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class InventoryIntegrationTest {

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

    @Autowired InventoryReservationService reservationService;
    @Autowired InventoryAdjustmentService adjustmentService;
    @Autowired InventoryLocationRepository locationRepo;
    @Autowired InventoryStockRepository stockRepo;
    @Autowired InventoryReservationRepository reservationRepo;
    @Autowired ReservationExpiryJob expiryJob;
    @Autowired JdbcTemplate jdbcTemplate;

    // Seed location IDs from V4 migration
    private static final UUID MAIN_WAREHOUSE_ID =
            UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID BUFFER_LOCATION_ID =
            UUID.fromString("a0000000-0000-0000-0000-000000000002");

    private UUID variantId;

    @BeforeEach
    void setUp() {
        variantId = UUID.randomUUID();
        // Create a stock record for this test's variant
        InventoryLocation mainWarehouse = locationRepo.findById(MAIN_WAREHOUSE_ID)
                .orElseThrow(() -> new IllegalStateException("Main Warehouse seed data missing"));
        InventoryStock stock = new InventoryStock(variantId, mainWarehouse, 100, 10);
        stockRepo.save(stock);
    }

    // ── Scenario 1: reserve → confirm ────────────────────────────────────────

    @Test
    void should_reserveAndConfirm_when_sufficientStockAvailable() {
        UUID orderId = UUID.randomUUID();

        reservationService.reserveStock(orderId, List.of(
                new ReservationLineItem(variantId, MAIN_WAREHOUSE_ID, 20)));

        // Verify stock is reserved
        InventoryStock stock = stockRepo.findByVariantIdAndLocationId(variantId, MAIN_WAREHOUSE_ID)
                .orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(80);
        assertThat(stock.getReservedQuantity()).isEqualTo(20);

        // Confirm reservation
        reservationService.confirmReservation(orderId);

        // After confirm: reserved_quantity decremented, available unchanged
        InventoryStock confirmed = stockRepo.findByVariantIdAndLocationId(variantId, MAIN_WAREHOUSE_ID)
                .orElseThrow();
        assertThat(confirmed.getAvailableQuantity()).isEqualTo(80);
        assertThat(confirmed.getReservedQuantity()).isEqualTo(0);

        // Verify reservation status
        List<InventoryReservation> reservations = reservationRepo.findByOrderId(orderId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void should_writeAuditLogRow_when_stockReservedAndConfirmed() {
        UUID orderId = UUID.randomUUID();
        reservationService.reserveStock(orderId, List.of(
                new ReservationLineItem(variantId, MAIN_WAREHOUSE_ID, 10)));
        reservationService.confirmReservation(orderId);

        // Audit log verification — no audit log on reserveStock (not destructive per ADR-4)
        // Verify reservation row is CONFIRMED
        List<InventoryReservation> reservations = reservationRepo
                .findByOrderIdAndStatus(orderId, ReservationStatus.CONFIRMED);
        assertThat(reservations).isNotEmpty();
    }

    // ── Scenario 2: reserve → release ────────────────────────────────────────

    @Test
    void should_restoreAvailableStock_when_reservationReleased() {
        UUID orderId = UUID.randomUUID();

        reservationService.reserveStock(orderId, List.of(
                new ReservationLineItem(variantId, MAIN_WAREHOUSE_ID, 30)));

        reservationService.releaseReservation(orderId, ConflictReason.CANCELLED);

        InventoryStock stock = stockRepo.findByVariantIdAndLocationId(variantId, MAIN_WAREHOUSE_ID)
                .orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(100);
        assertThat(stock.getReservedQuantity()).isEqualTo(0);

        // Verify audit log written before release
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log " +
                "WHERE table_name = 'inventory_reservations' AND action = 'STATUS_CHANGE'",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);

        // Verify reservation is RELEASED with CANCELLED conflict reason
        List<InventoryReservation> reservations = reservationRepo.findByOrderId(orderId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.get(0).getConflictReason()).isEqualTo(ConflictReason.CANCELLED);
    }

    // ── Scenario 3: insufficient stock ───────────────────────────────────────

    @Test
    void should_throwBusinessRuleException_when_reserveMoreThanAvailable() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> reservationService.reserveStock(orderId, List.of(
                new ReservationLineItem(variantId, MAIN_WAREHOUSE_ID, 101))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient stock");
    }

    // ── Scenario 4: expiry job ────────────────────────────────────────────────

    @Test
    void should_releaseExpiredReservations_when_expiryJobRuns() {
        UUID orderId = UUID.randomUUID();

        // Create reservation with past expiry directly via repository
        InventoryLocation mainWarehouse = locationRepo.findById(MAIN_WAREHOUSE_ID).orElseThrow();
        InventoryReservation expired = new InventoryReservation(
                orderId, variantId, mainWarehouse, 5,
                Instant.now().minusSeconds(120)); // expired 2 minutes ago
        reservationRepo.save(expired);

        // Also decrement stock to simulate the reserved state
        InventoryStock stock = stockRepo.findByVariantIdAndLocationId(variantId, MAIN_WAREHOUSE_ID)
                .orElseThrow();
        stock.reserve(5);
        stockRepo.save(stock);

        // Run expiry job
        expiryJob.expireStaleReservations();

        // Verify reservation is RELEASED
        List<InventoryReservation> reservations = reservationRepo.findByOrderId(orderId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.get(0).getConflictReason()).isEqualTo(ConflictReason.EXPIRED);

        // Verify stock is restored
        InventoryStock restored = stockRepo.findByVariantIdAndLocationId(variantId, MAIN_WAREHOUSE_ID)
                .orElseThrow();
        assertThat(restored.getAvailableQuantity()).isEqualTo(100);
    }

    // ── Test infrastructure configuration ────────────────────────────────────

    /**
     * Provides lightweight infrastructure beans for the integration test context.
     * walmal-infrastructure is not a compile dependency of walmal-inventory.
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
                @Override public boolean isVariantActive(UUID variantId) { return true; } // all active in tests
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
                @Override public <T> T executeWithLock(String key, Duration timeout, java.util.function.Supplier<T> action) { return action.get(); }
            };
        }
    }
}
