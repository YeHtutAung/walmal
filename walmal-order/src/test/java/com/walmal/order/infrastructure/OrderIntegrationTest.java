package com.walmal.order.infrastructure;

import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.order.application.OrderCreationService;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.application.dto.OrderDetailDto;
import com.walmal.order.application.dto.OrderLineItem;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.ShippingAddress;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full integration test for the walmal-order module.
 *
 * <p>Uses a Testcontainers PostgreSQL instance. Flyway migrations V1–V5 are applied
 * automatically via classpath:db/migration.</p>
 *
 * <p>Infrastructure beans (AuditService, CacheService, DomainEventPublisher, etc.)
 * are provided by the inner {@link TestInfrastructureConfig}. Cross-module service
 * interfaces (ProductCatalogService, ProductPricingService, InventoryReservationService)
 * are stubbed to control test scenarios without real module dependencies.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OrderIntegrationTest {

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

    @Autowired OrderCreationService orderCreationService;
    @Autowired OrderQueryService orderQueryService;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID VARIANT_ID = UUID.randomUUID();
    private static final UUID LOCATION_ID = UUID.randomUUID();
    private static final ShippingAddress ADDRESS =
            new ShippingAddress("1 Main St", null, "Springfield", "US", "12345");

    // ── Scenario 1: create order → confirmed ─────────────────────────────────

    @Test
    void should_createAndConfirmOrder_when_happyPath() {
        UUID userId = UUID.randomUUID();
        List<OrderLineItem> items = List.of(new OrderLineItem(VARIANT_ID, LOCATION_ID, 2));

        UUID orderId = orderCreationService.createOrder(userId, items, ADDRESS, "USD");

        assertThat(orderId).isNotNull();

        OrderDetailDto detail = orderQueryService.getOrder(orderId);
        assertThat(detail.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(detail.userId()).isEqualTo(userId);
        assertThat(detail.currency()).isEqualTo("USD");
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).skuSnapshot()).isEqualTo("SKU-001");
    }

    // ── Scenario 2: cancel a pending order ────────────────────────────────────

    @Test
    void should_cancelOrder_when_orderIsPending() {
        UUID userId = UUID.randomUUID();
        // Payment gateway is stubbed to always succeed, so we test cancel via
        // direct repository manipulation to get a PENDING state
        // For integration, we use the seeded PENDING order from V5 migration
        UUID seededOrderId = UUID.fromString("c0000000-0000-0000-0000-000000000001");
        UUID seededUserId = UUID.fromString("a0000000-0000-0000-0000-000000000001");

        orderCreationService.cancelOrder(seededOrderId, seededUserId);

        OrderDetailDto detail = orderQueryService.getOrder(seededOrderId);
        assertThat(detail.status()).isEqualTo(OrderStatus.CANCELLED);

        // Verify audit log was written
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE table_name = 'order_orders' AND action = 'STATUS_CHANGE'",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    // ── Scenario 3: order not found ───────────────────────────────────────────

    @Test
    void should_throwResourceNotFoundException_when_orderNotFound() {
        assertThatThrownBy(() -> orderQueryService.getOrder(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Scenario 4: cancel non-pending order ─────────────────────────────────

    @Test
    void should_throwBusinessRuleException_when_cancellingNonPendingOrder() {
        UUID userId = UUID.randomUUID();
        List<OrderLineItem> items = List.of(new OrderLineItem(VARIANT_ID, LOCATION_ID, 1));

        UUID orderId = orderCreationService.createOrder(userId, items, ADDRESS, "USD");
        // Order is now CONFIRMED due to stub payment always succeeding

        assertThatThrownBy(() -> orderCreationService.cancelOrder(orderId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    // ── Test infrastructure configuration ────────────────────────────────────

    /**
     * Provides lightweight infrastructure beans for the integration test context.
     * walmal-infrastructure is not a compile dependency of walmal-order.
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
