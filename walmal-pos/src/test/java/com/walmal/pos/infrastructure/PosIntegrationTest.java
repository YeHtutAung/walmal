package com.walmal.pos.infrastructure;

import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.inventory.application.ConflictResolutionResult;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.order.application.OrderCreationService;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.application.dto.OrderDetailDto;
import com.walmal.order.application.dto.OrderLineItem;
import com.walmal.order.application.dto.OrderSummaryDto;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.ShippingAddress;
import com.walmal.pos.application.PosSaleService;
import com.walmal.pos.application.PosSyncService;
import com.walmal.pos.application.PosTerminalService;
import com.walmal.pos.application.dto.*;
import com.walmal.pos.domain.QueueStatus;
import com.walmal.pos.domain.SaleMode;
import com.walmal.pos.domain.SyncStatus;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full integration test for the walmal-pos module.
 *
 * <p>Uses a Testcontainers PostgreSQL instance. Flyway migrations V1–V6 are applied
 * automatically via classpath:db/migration.</p>
 *
 * <p>Infrastructure beans and cross-module service interfaces are provided by
 * {@link TestInfrastructureConfig}. Cross-module services are stubbed to control
 * test scenarios without real module dependencies.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PosIntegrationTest {

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

    @Autowired PosTerminalService posTerminalService;
    @Autowired PosSaleService posSaleService;
    @Autowired PosSyncService posSyncService;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID VARIANT_ID = UUID.randomUUID();
    private static final UUID LOCATION_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    // ── Scenario 1: online sale persisted ─────────────────────────────────────

    @Test
    void should_persistPosSale_when_onlineSaleRecorded() {
        UUID terminalId = posTerminalService.registerTerminal("Integration Test Terminal", LOCATION_ID);

        List<PosSaleLineItem> items = List.of(new PosSaleLineItem(VARIANT_ID, LOCATION_ID, 1));

        PosSaleDto sale = posSaleService.recordOnlineSale(
                terminalId, items, UUID.randomUUID(), "SGD", null);

        assertThat(sale).isNotNull();
        assertThat(sale.saleMode()).isEqualTo(SaleMode.ONLINE);
        assertThat(sale.syncStatus()).isEqualTo(SyncStatus.N_A);
        assertThat(sale.onlineOrderId()).isNotNull();

        // Verify DB row exists
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pos_sales WHERE id = ?::uuid", Integer.class, sale.id().toString());
        assertThat(count).isEqualTo(1);

        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pos_sale_items WHERE sale_id = ?::uuid",
                Integer.class, sale.id().toString());
        assertThat(itemCount).isEqualTo(1);
    }

    // ── Scenario 2: offline sync queue persisted ───────────────────────────────

    @Test
    void should_persistSyncQueue_when_offlineSyncSubmitted() {
        UUID terminalId = posTerminalService.registerTerminal("Offline Sync Terminal", LOCATION_ID);

        OfflineSaleLineItem lineItem = new OfflineSaleLineItem(
                VARIANT_ID, LOCATION_ID, 1,
                BigDecimal.valueOf(29.99), "SGD",
                "Integration Product", "INT-SKU-001");

        OfflineSalePayload payload = new OfflineSalePayload(
                UUID.randomUUID(), List.of(lineItem), "SGD",
                Instant.now().minusSeconds(300));

        SyncResultDto result = posSyncService.submitOfflineSync(terminalId, List.of(payload));

        assertThat(result.totalSubmitted()).isEqualTo(1);
        // The sync may succeed or fail depending on resolveConflict stub — check queue was written
        Integer queueCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pos_sync_queue WHERE terminal_id = ?::uuid",
                Integer.class, terminalId.toString());
        assertThat(queueCount).isGreaterThanOrEqualTo(1);
    }

    // ── Scenario 3: terminal deactivation ─────────────────────────────────────

    @Test
    void should_deactivateTerminal_and_writeAuditLog() {
        UUID terminalId = posTerminalService.registerTerminal("Terminal To Deactivate", LOCATION_ID);

        posTerminalService.deactivateTerminal(terminalId);

        PosTerminalDto dto = posTerminalService.getTerminal(terminalId);
        assertThat(dto.status().name()).isEqualTo("INACTIVE");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE table_name = 'pos_terminals' AND action = 'STATUS_CHANGE'",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    // ── Test infrastructure configuration ────────────────────────────────────

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
                    return Optional.of(stubVariant());
                }
                @Override
                public com.walmal.product.application.dto.ProductDetailDto getProductDetails(UUID productId) { return null; }
                @Override
                public boolean isVariantActive(UUID variantId) { return true; }
                @Override
                public Optional<VariantSummaryDto> findVariantById(UUID variantId) {
                    return Optional.of(stubVariant());
                }
                private VariantSummaryDto stubVariant() {
                    return new VariantSummaryDto(
                            UUID.randomUUID(), UUID.randomUUID(),
                            "INT-SKU-001", "BC-001", "Integration Test Product",
                            "Blue", "L", ProductStatus.ACTIVE);
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
                    return Optional.of(new PriceDto(variantId, BigDecimal.valueOf(29.99), "SGD", Instant.now()));
                }
                @Override
                public PriceDto getPriceForVariant(UUID variantId) {
                    return new PriceDto(variantId, BigDecimal.valueOf(29.99), "SGD", Instant.now());
                }
            };
        }

        @Bean
        @Primary
        InventoryReservationService stubInventoryReservationService() {
            return new InventoryReservationService() {
                @Override
                public void reserveStock(UUID orderId, List<InventoryReservationService.ReservationLineItem> items) {}
                @Override
                public void confirmReservation(UUID orderId) {}
                @Override
                public void releaseReservation(UUID orderId, com.walmal.inventory.domain.ConflictReason reason) {}
                @Override
                public ConflictResolutionResult resolveConflict(UUID posSaleId, UUID variantId,
                                                                UUID locationId, int quantity,
                                                                Instant posSaleTimestamp, UUID webOrderId) {
                    return ConflictResolutionResult.noConflict(locationId);
                }
            };
        }

        @Bean
        @Primary
        OrderCreationService stubOrderCreationService() {
            return new OrderCreationService() {
                @Override
                public UUID createOrder(UUID userId, List<OrderLineItem> items,
                                        ShippingAddress shippingAddress, String currency) {
                    return UUID.randomUUID();
                }
                @Override
                public UUID createGuestOrder(String guestEmail, List<OrderLineItem> items,
                                             ShippingAddress shippingAddress, String currency) {
                    return UUID.randomUUID();
                }
                @Override
                public void cancelOrder(UUID orderId, UUID actorId) {}
            };
        }

        @Bean
        @Primary
        OrderQueryService stubOrderQueryService() {
            return new OrderQueryService() {
                @Override
                public OrderDetailDto getOrder(UUID orderId) {
                    throw new ResourceNotFoundException("Order", orderId);
                }
                @Override
                public OrderStatus getOrderStatus(UUID orderId) { return OrderStatus.CONFIRMED; }
                @Override
                public org.springframework.data.domain.Page<OrderSummaryDto> listOrdersByUser(
                        UUID userId, org.springframework.data.domain.Pageable pageable) {
                    return org.springframework.data.domain.Page.empty();
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
