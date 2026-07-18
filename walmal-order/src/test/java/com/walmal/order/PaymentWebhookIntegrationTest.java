package com.walmal.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import com.walmal.common.payment.PaymentWebhookVerifier;
import com.walmal.common.payment.VerifiedWebhookEvent;
import com.walmal.common.payment.WebhookVerificationException;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.order.application.PaymentWebhookService;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for the Stripe webhook reconciliation log
 * ({@code PaymentWebhookController} → {@code PaymentWebhookServiceImpl} →
 * {@code PaymentWebhookEventStore}), against a real Postgres instance.
 *
 * <p>Exercises the exact five TDD behaviors from the production-deployment
 * plan's Task 4:
 * <ol>
 *   <li>valid signature + {@code payment_intent.succeeded} matching an
 *       order's {@code payment_reference} → {@code MATCHED} row</li>
 *   <li>valid signature, intent matches no order → {@code UNMATCHED} row</li>
 *   <li>duplicate {@code event_id} → no second row (idempotent)</li>
 *   <li>unknown event type → no row</li>
 *   <li>bad signature → exception, nothing persisted</li>
 * </ol>
 *
 * <p>walmal-order does not (and must not) depend on walmal-infrastructure or
 * stripe-java, so {@link TestInfrastructureConfig} supplies its own
 * {@link PaymentWebhookVerifier} test double that performs genuine
 * HMAC-SHA256 verification using Stripe's own signing scheme
 * ({@code t=<ts>,v1=hex(HMAC_SHA256(secret, "<ts>.<payload>"))}) — the same
 * scheme {@code StripeWebhookVerifierImplTest} (walmal-infrastructure) proves
 * against the real Stripe SDK, and the same scheme the live curl
 * verification script uses. This test proves the controller/service/
 * repository/persistence layer; the SDK-specific "extract from raw JSON, not
 * the data-object deserializer" trap is proven separately at the
 * infrastructure layer.</p>
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PaymentWebhookIntegrationTest {

    static final String TEST_WEBHOOK_SECRET = "whsec_integration_test_secret";

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

    @Autowired PaymentWebhookService paymentWebhookService;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM payment_webhook_events");
        jdbcTemplate.update("DELETE FROM order_orders");
    }

    // ── Behavior 1: matched order ────────────────────────────────────────────

    @Test
    void should_recordMatchedRow_when_intentMatchesAnOrdersPaymentReference() {
        UUID orderId = seedOrder("pi_matched_123");
        String payload = paymentIntentEventPayload("evt_matched_1", "payment_intent.succeeded", "pi_matched_123");
        String signatureHeader = sign(payload, TEST_WEBHOOK_SECRET);

        paymentWebhookService.handle(payload, signatureHeader);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM payment_webhook_events WHERE event_id = ?", "evt_matched_1");
        assertThat(row.get("event_type")).isEqualTo("payment_intent.succeeded");
        assertThat(row.get("payment_intent_id")).isEqualTo("pi_matched_123");
        assertThat(row.get("order_id")).isEqualTo(orderId);
        assertThat(row.get("status")).isEqualTo("MATCHED");
    }

    // ── Behavior 2: unmatched intent ─────────────────────────────────────────

    @Test
    void should_recordUnmatchedRow_when_intentMatchesNoOrder() {
        String payload = paymentIntentEventPayload("evt_unmatched_1", "payment_intent.succeeded", "pi_orphan_999");
        String signatureHeader = sign(payload, TEST_WEBHOOK_SECRET);

        paymentWebhookService.handle(payload, signatureHeader);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM payment_webhook_events WHERE event_id = ?", "evt_unmatched_1");
        assertThat(row.get("order_id")).isNull();
        assertThat(row.get("status")).isEqualTo("UNMATCHED");
    }

    // ── Behavior 3: idempotent on duplicate event_id ─────────────────────────

    @Test
    void should_notCreateSecondRow_when_eventIdIsDuplicate() {
        UUID orderId = seedOrder("pi_dup_123");
        String payload = paymentIntentEventPayload("evt_dup_1", "payment_intent.succeeded", "pi_dup_123");
        String signatureHeader = sign(payload, TEST_WEBHOOK_SECRET);

        paymentWebhookService.handle(payload, signatureHeader);
        paymentWebhookService.handle(payload, signatureHeader); // Stripe retry of the same delivery

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_events WHERE event_id = ?", Integer.class, "evt_dup_1");
        assertThat(count).isEqualTo(1);

        // Sanity: the one surviving row is still correct, not overwritten with junk.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM payment_webhook_events WHERE event_id = ?", "evt_dup_1");
        assertThat(row.get("order_id")).isEqualTo(orderId);
    }

    // ── Behavior 4: unknown event type ───────────────────────────────────────

    @Test
    void should_notPersistAnyRow_when_eventTypeIsUnhandled() {
        String payload = "{\"id\":\"evt_unknown_1\",\"type\":\"customer.created\","
                + "\"data\":{\"object\":{\"object\":\"customer\"}}}";
        String signatureHeader = sign(payload, TEST_WEBHOOK_SECRET);

        paymentWebhookService.handle(payload, signatureHeader);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_events WHERE event_id = ?", Integer.class, "evt_unknown_1");
        assertThat(count).isZero();
    }

    // ── Behavior 5: bad signature ─────────────────────────────────────────────

    @Test
    void should_throwAndPersistNothing_when_signatureInvalid() {
        String payload = paymentIntentEventPayload("evt_bad_sig_1", "payment_intent.succeeded", "pi_bad_sig");
        String wrongSignature = sign(payload, "whsec_a_totally_different_secret");

        assertThatThrownBy(() -> paymentWebhookService.handle(payload, wrongSignature))
                .isInstanceOf(WebhookVerificationException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_events WHERE event_id = ?", Integer.class, "evt_bad_sig_1");
        assertThat(count).isZero();
    }

    @Test
    void should_throwAndPersistNothing_when_signatureHeaderMissing() {
        String payload = paymentIntentEventPayload("evt_missing_sig_1", "payment_intent.succeeded", "pi_missing_sig");

        assertThatThrownBy(() -> paymentWebhookService.handle(payload, null))
                .isInstanceOf(WebhookVerificationException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_events WHERE event_id = ?", Integer.class, "evt_missing_sig_1");
        assertThat(count).isZero();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static String paymentIntentEventPayload(String eventId, String eventType, String paymentIntentId) {
        return "{"
                + "\"id\":\"" + eventId + "\","
                + "\"type\":\"" + eventType + "\","
                + "\"data\":{\"object\":{\"id\":\"" + paymentIntentId + "\",\"object\":\"payment_intent\"}}"
                + "}";
    }

    private static String sign(String payload, String secret) {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        return "t=" + timestamp + ",v1=" + hmacSha256Hex(secret, signedPayload);
    }

    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(rawHmac.length * 2);
            for (byte b : rawHmac) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Inserts an order directly via JDBC with a known {@code payment_reference},
     * bypassing the entity/repository layer for determinism (same rationale as
     * {@code OrderDailySummaryIntegrationTest#seedOrder}).
     */
    private UUID seedOrder(String paymentReference) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO order_orders "
                + "(id, user_id, status, currency, total_amount, shipping_address, payment_reference, payment_status) "
                + "VALUES (?, ?, 'CONFIRMED', 'USD', 10.00, '{\"line1\":\"1 Main St\",\"city\":\"Springfield\","
                + "\"country\":\"US\",\"postalCode\":\"12345\"}'::jsonb, ?, 'SUCCESS')",
                orderId, UUID.randomUUID(), paymentReference);
        return orderId;
    }

    // ── Test infrastructure configuration ────────────────────────────────────
    //
    // Mirrors OrderIntegrationTest.TestInfrastructureConfig: walmal-infrastructure
    // is not a compile dependency of walmal-order, and OrderTestApplication's
    // component scan pulls in every @Service in this module, so all of these
    // stub beans are required for the context to start even though this test
    // only calls PaymentWebhookService. PaymentWebhookVerifier has no bean at
    // all outside walmal-infrastructure, so it MUST be supplied here (the others
    // are copied from the established pattern for context-boot completeness).

    @TestConfiguration
    static class TestInfrastructureConfig {

        @Bean
        @Primary
        PaymentWebhookVerifier hmacPaymentWebhookVerifier() {
            return (payload, signatureHeader) -> {
                if (signatureHeader == null || signatureHeader.isBlank()) {
                    throw new WebhookVerificationException("Missing Stripe-Signature header");
                }
                if (!verifySignature(payload, signatureHeader, TEST_WEBHOOK_SECRET)) {
                    throw new WebhookVerificationException("Invalid Stripe webhook signature");
                }
                return extractVerifiedEvent(payload);
            };
        }

        private static boolean verifySignature(String payload, String signatureHeader, String secret) {
            String timestamp = null;
            String v1 = null;
            for (String part : signatureHeader.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0])) timestamp = kv[1];
                else if ("v1".equals(kv[0])) v1 = kv[1];
            }
            if (timestamp == null || v1 == null) return false;
            String expected = hmacSha256Hex(secret, timestamp + "." + payload);
            return expected.equalsIgnoreCase(v1);
        }

        private static VerifiedWebhookEvent extractVerifiedEvent(String payload) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(payload);
                String eventId = root.path("id").asText(null);
                String eventType = root.path("type").asText(null);
                JsonNode idNode = root.path("data").path("object").path("id");
                String paymentIntentId = idNode.isMissingNode() || idNode.isNull() ? null : idNode.asText();
                return new VerifiedWebhookEvent(eventId, eventType, paymentIntentId);
            } catch (Exception e) {
                throw new WebhookVerificationException("Malformed webhook payload", e);
            }
        }

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

        private static String hmacSha256Hex(String secret, String message) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(rawHmac.length * 2);
                for (byte b : rawHmac) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
