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
import com.walmal.order.application.dto.OrderAdminSummaryDto;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving the admin order-search JPQL
 * ({@code OrderRepository#searchByIdPrefixOrGuestEmail}) executes correctly
 * against real Postgres, end to end through {@link OrderAdminService#searchOrders}.
 *
 * <p>Unit tests for {@code OrderAdminServiceImpl} mock the repository, so the
 * parts of this query that only a real database can validate are covered here:</p>
 * <ul>
 *   <li><b>{@code lower(CAST(o.id AS string))}</b> — the UUID column really casts
 *       to its canonical lowercase text form, and an UPPERCASE pasted ID still
 *       matches after the service lowercases the query.</li>
 *   <li><b>Null guest email</b> — a registered-customer row (guestEmail NULL) is
 *       still matched via the ID predicate and does not error the OR.</li>
 * </ul>
 *
 * <p>Same Testcontainers setup pattern as {@link OrderDailySummaryIntegrationTest}.
 * Tagged {@code "integration"} so it runs only with {@code -DexcludedGroups=}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OrderSearchIntegrationTest {

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

    private static final UUID GUEST_ORDER_ID =
            UUID.fromString("aaaa1111-1111-1111-1111-111111111111");
    private static final UUID REGISTERED_ORDER_ID =
            UUID.fromString("bbbb2222-2222-2222-2222-222222222222");
    private static final String GUEST_EMAIL = "search-me@example.com";

    private static final String SHIPPING_ADDRESS_JSON =
            "{\"line1\":\"1 Main St\",\"city\":\"Springfield\",\"country\":\"US\",\"postalCode\":\"12345\"}";

    @BeforeEach
    void reseedOrders() {
        // Clear the V5 Flyway dev-seed row (and prior test residue) so the two
        // seeded orders below are the only rows the search can hit.
        jdbcTemplate.update("DELETE FROM order_orders");

        // Order 1: guest order — userId NULL, guest email set.
        seedOrder(GUEST_ORDER_ID, null, GUEST_EMAIL);

        // Order 2: registered-customer order — userId set, guest email NULL.
        // This is the row that proves lower(o.guestEmail) over NULL is harmless.
        seedOrder(REGISTERED_ORDER_ID, UUID.randomUUID(), null);
    }

    // ── ID-prefix matching + case folding on the CAST ─────────────────────────

    @Test
    void should_matchOrderByIdPrefix_when_queryIsLowercase() {
        Page<OrderAdminSummaryDto> page =
                orderAdminService.searchOrders("aaaa1111", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(OrderAdminSummaryDto::id)
                .containsExactly(GUEST_ORDER_ID);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void should_matchOrderByIdPrefix_when_queryIsUppercase() {
        // Postgres renders the UUID lowercase; the CAST result must fold against
        // the lowercased query so a pasted uppercase ID still matches.
        Page<OrderAdminSummaryDto> page =
                orderAdminService.searchOrders("AAAA1111", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(OrderAdminSummaryDto::id)
                .containsExactly(GUEST_ORDER_ID);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    // ── Guest-email substring matching ─────────────────────────────────────────

    @Test
    void should_matchOrderByGuestEmailSubstring_when_queryIsEmailFragment() {
        Page<OrderAdminSummaryDto> page =
                orderAdminService.searchOrders("search-me", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(OrderAdminSummaryDto::id)
                .containsExactly(GUEST_ORDER_ID);
    }

    // ── Null guest email row still matchable by ID ─────────────────────────────

    @Test
    void should_matchNullGuestEmailOrderByIdPrefix_when_queryMatchesItsId() {
        Page<OrderAdminSummaryDto> page =
                orderAdminService.searchOrders("bbbb", PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(OrderAdminSummaryDto::id)
                .containsExactly(REGISTERED_ORDER_ID);
    }

    // ── No match ───────────────────────────────────────────────────────────────

    @Test
    void should_returnEmptyPage_when_queryMatchesNothing() {
        Page<OrderAdminSummaryDto> page =
                orderAdminService.searchOrders("zzzz", PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0);
    }

    // ── Seeding helper ─────────────────────────────────────────────────────────

    /**
     * Inserts an order row via plain JDBC with an explicit, known UUID so the
     * ID-prefix assertions are deterministic ({@code Order#onCreate()} would
     * otherwise assign a random ID and timestamp through the repository path).
     */
    private void seedOrder(UUID orderId, UUID userId, String guestEmail) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO order_orders " +
                "(id, user_id, guest_email, status, currency, total_amount, shipping_address, " +
                " payment_status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                orderId,
                userId,
                guestEmail,
                "PENDING",
                "USD",
                new BigDecimal("25.00"),
                SHIPPING_ADDRESS_JSON,
                "PENDING",
                Timestamp.from(now),
                Timestamp.from(now));
    }

    // ── Test infrastructure configuration ────────────────────────────────────
    //
    // Mirrors OrderDailySummaryIntegrationTest.TestInfrastructureConfig:
    // walmal-infrastructure is not a compile dependency of walmal-order, and
    // OrderTestApplication's component scan pulls in every @Service in this
    // module, so all of these stub beans are required for the context to start
    // even though this test only calls OrderAdminService.

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

                @Override
                public java.util.List<com.walmal.product.application.dto.CategoryProductVariantRow> getAllCategoryProductVariantMappings() {
                    return java.util.List.of();
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
            return (orderId, paymentReference, amount, currency) ->
                    new PaymentResult(paymentReference, PaymentStatus.SUCCESS);
        }
    }
}
