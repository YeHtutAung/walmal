package com.walmal.auth.infrastructure;

import com.walmal.auth.api.dto.LoginRequest;
import com.walmal.auth.api.dto.RegisterRequest;
import com.walmal.auth.api.dto.TokenResponse;
import com.walmal.auth.application.AuthService;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.JwtProperties;
import com.walmal.auth.domain.User;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the auth module.
 * Requires Docker to run Testcontainers PostgreSQL.
 * Skipped automatically when Docker is not available.
 *
 * <p>Uses Testcontainers PostgreSQL with Flyway migrations (V1 + V2) applied.
 * Infrastructure beans (CacheService, DomainEventPublisher, AuditService) are
 * provided as in-memory stubs — walmal-infrastructure is not on the classpath.</p>
 *
 * <p>Run integration tests explicitly: {@code mvn test -Dgroups=integration}</p>
 */
@Tag("integration")
@SpringBootTest(classes = AuthIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-minimum-256-bits-for-hs256-algorithm-padding",
        "walmal.jwt.access-token-expire-minutes=15",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class AuthIntegrationTest {

    /**
     * PostgreSQL container started manually (not via @Testcontainers) so that we
     * can check Docker availability BEFORE attempting container startup.
     */
    static final PostgreSQLContainer<?> postgres;

    static {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }

        if (dockerAvailable) {
            postgres = new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("walmal_test")
                    .withUsername("walmal")
                    .withPassword("walmal");
            postgres.start();
        } else {
            postgres = null;
        }
    }

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        Assumptions.assumeTrue(postgres != null,
                "Docker not available — skipping AuthIntegrationTest");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenValidationService tokenValidationService;

    @Autowired
    private UserRepository userRepository;

    // ── Migration ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_applyFlywayMigrationsCleanly_when_postgresContainerStarted")
    void should_applyFlywayMigrationsCleanly_when_postgresContainerStarted() {
        Optional<User> admin = userRepository.findByUsername("admin");
        assertThat(admin).isPresent();
        assertThat(admin.get().isActive()).isTrue();
        assertThat(admin.get().getEmail()).isEqualTo("admin@walmal.local");
    }

    // ── BCrypt seed hash ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_matchAdminPassword_when_seedHashVerifiedWithBCrypt")
    void should_matchAdminPassword_when_seedHashVerifiedWithBCrypt() {
        User admin = userRepository.findByUsername("admin")
                .orElseThrow(() -> new AssertionError("Seed user 'admin' not found"));

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        assertThat(encoder.matches("admin123", admin.getPasswordHash())).isTrue();
    }

    // ── Full login flow ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnValidJwtAccessToken_when_adminLogsIn")
    void should_returnValidJwtAccessToken_when_adminLogsIn() {
        TokenResponse response = authService.login(new LoginRequest("admin", "admin123"));

        assertThat(response).isNotNull();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).contains(":");

        assertThat(tokenValidationService.isValid(response.accessToken())).isTrue();
        assertThat(tokenValidationService.extractUsername(response.accessToken())).isEqualTo("admin");
        assertThat(tokenValidationService.extractRole(response.accessToken())).isEqualTo("ADMIN");
    }

    // ── Register + login round-trip ───────────────────────────────────────────

    @Test
    @DisplayName("should_allowLoginAfterRegistration_when_newUserRegisters")
    void should_allowLoginAfterRegistration_when_newUserRegisters() {
        String username = "testuser_" + UUID.randomUUID().toString().substring(0, 8);
        authService.register(new RegisterRequest(username, username + "@example.com", "password123", null));

        TokenResponse loginResponse = authService.login(new LoginRequest(username, "password123"));
        assertThat(tokenValidationService.isValid(loginResponse.accessToken())).isTrue();
        assertThat(tokenValidationService.extractUsername(loginResponse.accessToken())).isEqualTo(username);
        assertThat(tokenValidationService.extractRole(loginResponse.accessToken())).isEqualTo("CUSTOMER");
    }

    // ── Test configuration ────────────────────────────────────────────────────

    @Configuration
    @EnableAutoConfiguration(exclude = {
            RabbitAutoConfiguration.class,
            MailSenderAutoConfiguration.class
    })
    @EnableConfigurationProperties(JwtProperties.class)
    @ComponentScan(basePackages = "com.walmal.auth")
    static class TestConfig {

        @Bean
        public CacheService cacheService() {
            return new InMemoryCacheService();
        }

        @Bean
        public DomainEventPublisher domainEventPublisher() {
            return new DomainEventPublisher() {
                @Override public void publish(DomainEvent event) { /* no-op */ }
                @Override public void publish(DomainEvent event, String routingKey) { /* no-op */ }
            };
        }

        @Bean
        public AuditService auditService() {
            return entry -> { /* no-op */ };
        }
    }

    static class InMemoryCacheService implements CacheService {
        private final Map<String, Object> store = new HashMap<>();

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            Object val = store.get(key);
            if (val == null) return Optional.empty();
            return Optional.of(type.cast(val));
        }

        @Override public <T> void put(String key, T value) { store.put(key, value); }
        @Override public <T> void put(String key, T value, Duration ttl) { store.put(key, value); }
        @Override public void evict(String key) { store.remove(key); }
        @Override public void evictByPrefix(String prefix) {
            store.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }
}
