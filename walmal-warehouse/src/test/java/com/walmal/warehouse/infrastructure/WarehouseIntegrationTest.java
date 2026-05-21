package com.walmal.warehouse.infrastructure;

import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.inventory.application.InventoryAdjustmentService;
import com.walmal.order.application.OrderFulfillmentService;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.domain.OrderStatus;
import com.walmal.warehouse.application.WarehouseFulfillmentService;
import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import com.walmal.warehouse.domain.FulfillmentStatus;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full integration test for the walmal-warehouse module.
 *
 * <p>Uses a Testcontainers PostgreSQL instance. Flyway migrations V1–V7 are applied
 * automatically via classpath:db/migration.</p>
 *
 * <p>Cross-module service interfaces are stubbed. Infrastructure beans are provided
 * by the inner {@link TestInfrastructureConfig}.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class WarehouseIntegrationTest {

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

    @Autowired WarehouseFulfillmentService fulfillmentService;
    @Autowired FulfillmentOrderRepository fulfillmentRepo;
    @Autowired FulfillmentLineRepository lineRepo;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID SEEDED_ORDER_ID =
            UUID.fromString("o0000000-0000-0000-0000-000000000001");
    private static final UUID SEEDED_USER_ID =
            UUID.fromString("a0000000-0000-0000-0000-000000000001");

    // ── Scenario 1: get seeded fulfillment ────────────────────────────────────

    @Test
    void should_returnFulfillment_when_seededOrderQueried() {
        FulfillmentDetailDto detail = fulfillmentService.getFulfillment(SEEDED_ORDER_ID);

        assertThat(detail.orderId()).isEqualTo(SEEDED_ORDER_ID);
        assertThat(detail.status()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(detail.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);  // stub returns CONFIRMED
        assertThat(detail.lines()).hasSize(1);
    }

    // ── Scenario 2: advance PENDING → PICKING ─────────────────────────────────

    @Test
    void should_advanceStatusToPicking_when_fulfillmentIsPending() {
        fulfillmentService.advanceStatus(SEEDED_ORDER_ID, FulfillmentStatus.PICKING, "Integration test");

        FulfillmentDetailDto detail = fulfillmentService.getFulfillment(SEEDED_ORDER_ID);
        assertThat(detail.status()).isEqualTo(FulfillmentStatus.PICKING);
    }

    // ── Scenario 3: fulfillment not found ────────────────────────────────────

    @Test
    void should_throwResourceNotFoundException_when_fulfillmentNotFound() {
        assertThatThrownBy(() -> fulfillmentService.getFulfillment(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Scenario 4: cancel — idempotent when no fulfillment ──────────────────

    @Test
    void should_doNothing_when_cancelCalledForUnknownOrder() {
        fulfillmentService.cancelFulfillment(UUID.randomUUID());
        // No exception — idempotent
    }

    // ── Scenario 5: cancel non-cancellable state ──────────────────────────────

    @Test
    void should_throwBusinessRuleException_when_cancellingPackedFulfillment() {
        fulfillmentService.advanceStatus(SEEDED_ORDER_ID, FulfillmentStatus.PICKING, null);
        fulfillmentService.advanceStatus(SEEDED_ORDER_ID, FulfillmentStatus.PACKED, null);

        assertThatThrownBy(() -> fulfillmentService.cancelFulfillment(SEEDED_ORDER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    // ── Test infrastructure configuration ────────────────────────────────────

    @TestConfiguration
    static class TestInfrastructureConfig {

        @Bean @Primary
        AuditService jdbcAuditService(JdbcTemplate jdbcTemplate) {
            return entry -> jdbcTemplate.update(
                    "INSERT INTO audit_log " +
                    "(id, table_name, record_id, action, old_value, new_value, performed_by, created_at) " +
                    "VALUES (gen_random_uuid(), ?, ?::uuid, ?, ?::jsonb, ?::jsonb, ?, NOW())",
                    entry.tableName(), entry.recordId().toString(),
                    entry.action().name(), entry.oldValue(), entry.newValue(), entry.performedBy());
        }

        @Bean @Primary
        CacheService noOpCacheService() {
            return new CacheService() {
                @Override public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }
                @Override public <T> void put(String key, T value) {}
                @Override public <T> void put(String key, T value, Duration ttl) {}
                @Override public void evict(String key) {}
                @Override public void evictByPrefix(String prefix) {}
            };
        }

        @Bean @Primary
        DomainEventPublisher noOpEventPublisher() {
            return new DomainEventPublisher() {
                @Override public void publish(DomainEvent event) {}
                @Override public void publish(DomainEvent event, String routingKey) {}
            };
        }

        @Bean @Primary
        FileStorageService noOpFileStorageService() {
            return new FileStorageService() {
                @Override public StoredFile upload(String b, String k, InputStream c, String ct, long s) {
                    return new StoredFile(k, b, ct, s);
                }
                @Override public InputStream download(String b, String k) { return InputStream.nullInputStream(); }
                @Override public void delete(String b, String k) {}
                @Override public String getPresignedUrl(String b, String k) { return "http://test/" + k; }
            };
        }

        @Bean @Primary
        DistributedLockService noOpLockService() {
            return new DistributedLockService() {
                @Override public boolean tryLock(String key, Duration t) { return true; }
                @Override public void unlock(String key) {}
                @Override public <T> T executeWithLock(String key, Duration t, Supplier<T> a) { return a.get(); }
            };
        }

        @Bean @Primary
        OrderFulfillmentService stubOrderFulfillmentService() {
            return orderId -> {};  // no-op stub
        }

        @Bean @Primary
        OrderQueryService stubOrderQueryService() {
            return new OrderQueryService() {
                @Override public com.walmal.order.application.dto.OrderDetailDto getOrder(UUID id) { return null; }
                @Override public org.springframework.data.domain.Page<com.walmal.order.application.dto.OrderSummaryDto>
                    listOrdersByUser(UUID u, org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
                @Override public OrderStatus getOrderStatus(UUID id) { return OrderStatus.CONFIRMED; }
            };
        }

        @Bean @Primary
        InventoryAdjustmentService stubInventoryAdjustmentService() {
            return new InventoryAdjustmentService() {
                @Override public void adjustStock(UUID v, UUID l, int d, String r, String p) {}
                @Override public void transferStock(UUID v, UUID f, UUID t, int q, String p) {}
                @Override public void updateLowStockThreshold(UUID v, UUID l, int th, String p) {}
            };
        }
    }
}
