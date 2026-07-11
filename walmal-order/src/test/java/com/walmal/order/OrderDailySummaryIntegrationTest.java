package com.walmal.order;

import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.order.application.OrderAdminService;
import com.walmal.order.application.dto.DailyOrderSummaryDto;
import com.walmal.order.domain.OrderStatus;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import com.walmal.product.domain.ProductStatus;
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
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving the daily-summary JPQL constructor-projection query
 * ({@code OrderRepository#findForDailySummary}) executes correctly against real
 * Postgres, end to end through {@link OrderAdminService#getDailySummary()}.
 *
 * <p>Unit tests for {@code OrderAdminServiceImpl} mock the repository, so a
 * subtle JPQL error (wrong field order, wrong entity name, a typo in the query
 * string) would compile fine and pass every existing test while being silently
 * broken at runtime. This test seeds real rows with precisely controlled
 * {@code created_at} timestamps and asserts on the actual bucketed output.</p>
 *
 * <p>Uses a Testcontainers PostgreSQL instance, same setup pattern as
 * {@link com.walmal.order.infrastructure.OrderIntegrationTest}.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OrderDailySummaryIntegrationTest {

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

    @Autowired OrderAdminService orderAdminService;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String SHIPPING_ADDRESS_JSON =
            "{\"line1\":\"1 Main St\",\"city\":\"Springfield\",\"country\":\"US\",\"postalCode\":\"12345\"}";

    @BeforeEach
    void cleanSeedData() {
        // V5__order_create_tables.sql inserts one dev-seed PENDING order with
        // created_at defaulting to NOW() at migration time, which would otherwise
        // land inside this test's 30-day window and pollute the zero-fill
        // assertions. Wipe it (and anything left by a prior test in this class)
        // so the window is fully under this test's control.
        jdbcTemplate.update("DELETE FROM order_orders");
    }

    @Test
    void should_returnThirtyZeroFilledDays_when_ordersSeededOnTwoDistinctDays() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate dayWithTwoOrders = today.minusDays(5);
        LocalDate dayWithOneOrder = today.minusDays(20);

        // Day A: one FULFILLED order ($120.50) and one PENDING order ($40.00).
        // orderCount must include both; revenue must include only the FULFILLED one.
        seedOrder(OrderStatus.FULFILLED, "USD", new BigDecimal("120.50"), instantOnDay(dayWithTwoOrders));
        seedOrder(OrderStatus.PENDING, "USD", new BigDecimal("40.00"), instantOnDay(dayWithTwoOrders));

        // Day B: a single PENDING order — counts toward orderCount, contributes zero revenue.
        seedOrder(OrderStatus.PENDING, "USD", new BigDecimal("75.25"), instantOnDay(dayWithOneOrder));

        List<DailyOrderSummaryDto> result = orderAdminService.getDailySummary();

        assertThat(result).hasSize(30);

        DailyOrderSummaryDto bucketA = findDay(result, dayWithTwoOrders);
        assertThat(bucketA.orderCount()).isEqualTo(2);
        assertThat(bucketA.revenue()).isEqualByComparingTo("120.50");
        assertThat(bucketA.currency()).isEqualTo("USD");

        DailyOrderSummaryDto bucketB = findDay(result, dayWithOneOrder);
        assertThat(bucketB.orderCount()).isEqualTo(1);
        assertThat(bucketB.revenue()).isEqualByComparingTo("0.00");

        List<DailyOrderSummaryDto> otherDays = result.stream()
                .filter(d -> !d.date().equals(dayWithTwoOrders) && !d.date().equals(dayWithOneOrder))
                .toList();
        assertThat(otherDays).hasSize(28);
        assertThat(otherDays).allSatisfy(d -> {
            assertThat(d.orderCount()).isEqualTo(0);
            assertThat(d.revenue()).isEqualByComparingTo(BigDecimal.ZERO);
        });

        // Every date in the 30-day window is represented exactly once, in ascending
        // consecutive order, ending at "today" per the service's own UTC clock.
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).date()).isEqualTo(result.get(i - 1).date().plusDays(1));
        }
        assertThat(result.get(result.size() - 1).date()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    private static Instant instantOnDay(LocalDate day) {
        return day.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    private static DailyOrderSummaryDto findDay(List<DailyOrderSummaryDto> result, LocalDate date) {
        return result.stream()
                .filter(d -> d.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a bucket for " + date + " but none was present"));
    }

    /**
     * Inserts an order row via plain JDBC rather than {@code OrderRepository.save()}.
     * {@link com.walmal.order.domain.Order#onCreate()} is a {@code @PrePersist} hook
     * that unconditionally stamps {@code createdAt = Instant.now()}, so there is no
     * way to control the persisted timestamp through the entity/repository path.
     * Going straight to SQL lets this test place rows on exact, known days while
     * still exercising the real {@code findForDailySummary} JPQL query against them.
     */
    private void seedOrder(OrderStatus status, String currency, BigDecimal amount, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO order_orders " +
                "(id, user_id, status, currency, total_amount, shipping_address, payment_status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                UUID.randomUUID(),
                UUID.randomUUID(),
                status.name(),
                currency,
                amount,
                SHIPPING_ADDRESS_JSON,
                status == OrderStatus.FULFILLED ? "SUCCESS" : "PENDING",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    // ── Test infrastructure configuration ────────────────────────────────────
    //
    // Mirrors OrderIntegrationTest.TestInfrastructureConfig: walmal-infrastructure
    // is not a compile dependency of walmal-order, and OrderTestApplication's
    // component scan pulls in every @Service in this module (not just
    // OrderAdminServiceImpl), so all of these stub beans are required for the
    // context to start even though this test only calls OrderAdminService.

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
        DistributedLockService noOpDistributedLockService() {
            return new DistributedLockService() {
                @Override public boolean tryLock(String key, Duration timeout) { return true; }
                @Override public void unlock(String key) {}
                @Override public <T> T executeWithLock(String key, Duration timeout, Supplier<T> action) { return action.get(); }
            };
        }

        @Bean
        @Primary
        ProductCatalogService stubProductCatalogService() {
            return new ProductCatalogService() {
                @Override
                public Optional<VariantSummaryDto> findVariantBySku(String sku) {
                    return Optional.of(new VariantSummaryDto(
                            UUID.randomUUID(), UUID.randomUUID(),
                            "SKU-001", "BC-001", "Integration Test Product",
                            "Red", "M", ProductStatus.ACTIVE));
                }

                @Override
                public com.walmal.product.application.dto.ProductDetailDto getProductDetails(UUID productId) {
                    return null;
                }

                @Override
                public boolean isVariantActive(UUID variantId) {
                    return true;
                }

                @Override
                public Optional<VariantSummaryDto> findVariantById(UUID variantId) {
                    return Optional.of(new VariantSummaryDto(
                            variantId, UUID.randomUUID(),
                            "SKU-001", "BC-001", "Integration Test Product",
                            "Red", "M", ProductStatus.ACTIVE));
                }
            };
        }

        @Bean
        @Primary
        ProductPricingService stubProductPricingService() {
            return new ProductPricingService() {
                @Override
                public Optional<PriceDto> getCurrentPrice(UUID variantId) {
                    return Optional.of(new PriceDto(variantId, BigDecimal.valueOf(49.99), "USD", Instant.now()));
                }

                @Override
                public PriceDto getPriceForVariant(UUID variantId) {
                    return new PriceDto(variantId, BigDecimal.valueOf(49.99), "USD", Instant.now());
                }
            };
        }

        @Bean
        @Primary
        InventoryReservationService stubInventoryReservationService() {
            return new InventoryReservationService() {
                @Override
                public void reserveStock(UUID orderId, List<InventoryReservationService.ReservationLineItem> items) {
                    // no-op stub
                }

                @Override
                public void confirmReservation(UUID orderId) {
                    // no-op stub
                }

                @Override
                public void releaseReservation(UUID orderId,
                                               com.walmal.inventory.domain.ConflictReason conflictReason) {
                    // no-op stub
                }

                @Override
                public com.walmal.inventory.application.ConflictResolutionResult resolveConflict(
                        UUID posSaleId, UUID variantId, UUID locationId,
                        int quantity, Instant posSaleTimestamp, UUID webOrderId) {
                    return com.walmal.inventory.application.ConflictResolutionResult.noConflict(locationId);
                }
            };
        }

        @Bean
        @Primary
        PaymentGatewayService stubPaymentGatewayService() {
            return (orderId, amount, currency) ->
                    new PaymentResult(UUID.randomUUID().toString(), PaymentStatus.SUCCESS);
        }
    }
}
