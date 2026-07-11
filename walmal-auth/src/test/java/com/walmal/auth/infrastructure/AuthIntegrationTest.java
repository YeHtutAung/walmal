package com.walmal.auth.infrastructure;

import com.walmal.auth.AuthTestApplication;
import com.walmal.auth.api.dto.CreateUserRequest;
import com.walmal.auth.api.dto.LoginRequest;
import com.walmal.auth.api.dto.RegisterRequest;
import com.walmal.auth.api.dto.TokenResponse;
import com.walmal.auth.api.dto.UpdateUserRequest;
import com.walmal.auth.api.dto.UserProfileResponse;
import com.walmal.auth.application.AuthService;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.JwtProperties;
import com.walmal.auth.domain.User;
import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    private CapturingAuditService auditService;

    @BeforeEach
    void clearAuditCapture() {
        auditService.clear();
    }

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

    // ── List users ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnPagedUserList_when_noFiltersApplied")
    void should_returnPagedUserList_when_noFiltersApplied() {
        Page<UserProfileResponse> page = authService.listUsers(null, null, PageRequest.of(0, 10));
        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isGreaterThan(0);
        assertThat(page.getContent()).isNotEmpty();
    }

    @Test
    @DisplayName("should_filterByRole_when_roleParamProvided")
    void should_filterByRole_when_roleParamProvided() {
        Page<UserProfileResponse> page = authService.listUsers("ADMIN", null, PageRequest.of(0, 10));
        assertThat(page.getContent()).allMatch(u -> u.role().equals("ADMIN"));
    }

    @Test
    @DisplayName("should_filterByActiveStatus_when_activeParamProvided")
    void should_filterByActiveStatus_when_activeParamProvided() {
        Page<UserProfileResponse> page = authService.listUsers(null, true, PageRequest.of(0, 10));
        assertThat(page.getContent()).allMatch(UserProfileResponse::isActive);
    }

    // ── Get user by ID ────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnUserProfile_when_validIdGiven")
    void should_returnUserProfile_when_validIdGiven() {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        UserProfileResponse response = authService.getUser(admin.getId());
        assertThat(response.username()).isEqualTo("admin");
        assertThat(response.role()).isEqualTo("ADMIN");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_getUserCalledWithUnknownId")
    void should_throwResourceNotFoundException_when_getUserCalledWithUnknownId() {
        assertThatThrownBy(() -> authService.getUser(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Update user ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_updateRole_when_adminChangesUserRole")
    void should_updateRole_when_adminChangesUserRole() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        UserProfileResponse created = authService.createUser(
                new CreateUserRequest("u_" + u, u + "@test.com", "pass1234", "STAFF"), "admin");

        UserProfileResponse updated = authService.updateUser(
                created.id(), new UpdateUserRequest("CASHIER", null), "admin");

        assertThat(updated.role()).isEqualTo("CASHIER");
        assertThat(updated.isActive()).isTrue();
    }

    @Test
    @DisplayName("should_deactivateUser_when_adminSetsActiveFalse")
    void should_deactivateUser_when_adminSetsActiveFalse() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        UserProfileResponse created = authService.createUser(
                new CreateUserRequest("u_" + u, u + "@test.com", "pass1234", "STAFF"), "admin");

        UserProfileResponse updated = authService.updateUser(
                created.id(), new UpdateUserRequest(null, false), "admin");

        assertThat(updated.isActive()).isFalse();
        assertThat(updated.role()).isEqualTo("STAFF");
    }

    @Test
    @DisplayName("should_writeAuditEntryWithCorrectValues_when_userRoleUpdated")
    void should_writeAuditEntryWithCorrectValues_when_userRoleUpdated() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        UserProfileResponse created = authService.createUser(
                new CreateUserRequest("u_" + u, u + "@test.com", "pass1234", "STAFF"), "admin");
        auditService.clear();

        authService.updateUser(created.id(), new UpdateUserRequest("CASHIER", null), "admin");

        assertThat(auditService.entries()).hasSize(1);
        AuditEntry entry = auditService.entries().get(0);
        assertThat(entry.tableName()).isEqualTo("auth_users");
        assertThat(entry.recordId()).isEqualTo(created.id());
        assertThat(entry.action()).isEqualTo(AuditAction.UPDATE);
        assertThat(entry.oldValue()).contains("STAFF");
        assertThat(entry.newValue()).contains("CASHIER");
        assertThat(entry.performedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_updateCalledWithUnknownId")
    void should_throwResourceNotFoundException_when_updateCalledWithUnknownId() {
        assertThatThrownBy(() -> authService.updateUser(
                UUID.randomUUID(), new UpdateUserRequest("STAFF", null), "admin"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_invalidRoleProvided")
    void should_throwBusinessRuleException_when_invalidRoleProvided() {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        assertThatThrownBy(() -> authService.updateUser(
                admin.getId(), new UpdateUserRequest("INVALID_ROLE", null), "admin"))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ── New roles from V11 ────────────────────────────────────────────────────

    @Test
    @DisplayName("should_loginAndEmitCorrectRoleClaim_when_warehouseManagerLogsIn")
    void should_loginAndEmitCorrectRoleClaim_when_warehouseManagerLogsIn() {
        TokenResponse response = authService.login(new LoginRequest("warehouse_manager", "wm123456"));

        assertThat(response).isNotNull();
        assertThat(tokenValidationService.isValid(response.accessToken())).isTrue();
        assertThat(tokenValidationService.extractUsername(response.accessToken())).isEqualTo("warehouse_manager");
        assertThat(tokenValidationService.extractRole(response.accessToken())).isEqualTo("WAREHOUSE_MANAGER");
    }

    @Test
    @DisplayName("should_loginAndEmitCorrectRoleClaim_when_warehouseStaffLogsIn")
    void should_loginAndEmitCorrectRoleClaim_when_warehouseStaffLogsIn() {
        TokenResponse response = authService.login(new LoginRequest("warehouse_staff", "ws123456"));

        assertThat(response).isNotNull();
        assertThat(tokenValidationService.isValid(response.accessToken())).isTrue();
        assertThat(tokenValidationService.extractUsername(response.accessToken())).isEqualTo("warehouse_staff");
        assertThat(tokenValidationService.extractRole(response.accessToken())).isEqualTo("WAREHOUSE_STAFF");
    }

    @Test
    @DisplayName("should_loginAndEmitCorrectRoleClaim_when_posOperatorLogsIn")
    void should_loginAndEmitCorrectRoleClaim_when_posOperatorLogsIn() {
        TokenResponse response = authService.login(new LoginRequest("pos_operator", "pos123456"));

        assertThat(response).isNotNull();
        assertThat(tokenValidationService.isValid(response.accessToken())).isTrue();
        assertThat(tokenValidationService.extractUsername(response.accessToken())).isEqualTo("pos_operator");
        assertThat(tokenValidationService.extractRole(response.accessToken())).isEqualTo("POS_OPERATOR");
    }

    @Test
    @DisplayName("should_acceptWarehouseManagerRole_when_createUserRequestSubmitted")
    void should_acceptWarehouseManagerRole_when_createUserRequestSubmitted() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        UserProfileResponse created = authService.createUser(
                new CreateUserRequest("wm_" + u, u + "@test.com", "pass1234", "WAREHOUSE_MANAGER"), "admin");

        assertThat(created.role()).isEqualTo("WAREHOUSE_MANAGER");
    }

    @Test
    @DisplayName("should_acceptPosOperatorRole_when_createUserRequestSubmitted")
    void should_acceptPosOperatorRole_when_createUserRequestSubmitted() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        UserProfileResponse created = authService.createUser(
                new CreateUserRequest("pos_" + u, u + "@test.com", "pass1234", "POS_OPERATOR"), "admin");

        assertThat(created.role()).isEqualTo("POS_OPERATOR");
    }

    @Test
    @DisplayName("should_filterByWarehouseManagerRole_when_roleFilterApplied")
    void should_filterByWarehouseManagerRole_when_roleFilterApplied() {
        Page<UserProfileResponse> page = authService.listUsers("WAREHOUSE_MANAGER", null, PageRequest.of(0, 10));
        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent()).allMatch(u -> u.role().equals("WAREHOUSE_MANAGER"));
    }

    // ── Test configuration ────────────────────────────────────────────────────

    @Configuration
    @EnableAutoConfiguration(exclude = {
            RabbitAutoConfiguration.class,
            MailSenderAutoConfiguration.class
    })
    @EnableConfigurationProperties(JwtProperties.class)
    // excludeFilters: AuthTestApplication is itself @SpringBootApplication (i.e.
    // @EnableAutoConfiguration + @ComponentScan). Without this exclusion, this scan
    // picks it up as a plain @Configuration bean, whose own @EnableAutoConfiguration
    // fires a second time alongside this class's — duplicate-registering
    // JPA-repository beans (e.g. userRepository) and failing context startup.
    @ComponentScan(basePackages = "com.walmal.auth",
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = AuthTestApplication.class))
    // Explicit entity/repository scan: this class (com.walmal.auth.infrastructure)
    // is a sibling of com.walmal.auth.domain (where the User @Entity lives), not an
    // ancestor. Spring Boot's implicit @EnableAutoConfiguration-driven entity/repo
    // scan defaults to this class's own package, which would miss the domain
    // package entirely ("Not a managed type: class com.walmal.auth.domain.User")
    // now that AuthTestApplication (whose package is the com.walmal.auth parent) is
    // excluded above. Declaring both explicitly removes the dependency on implicit
    // package inference altogether.
    @EntityScan(basePackages = "com.walmal.auth.domain")
    @EnableJpaRepositories(basePackages = "com.walmal.auth.infrastructure")
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
        public CapturingAuditService auditService() {
            return new CapturingAuditService();
        }
    }

    static class CapturingAuditService implements AuditService {
        private final List<AuditEntry> captured = new ArrayList<>();

        @Override
        public void log(AuditEntry entry) {
            captured.add(entry);
        }

        public List<AuditEntry> entries() {
            return List.copyOf(captured);
        }

        public void clear() {
            captured.clear();
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

        @Override
        public long increment(String key, Duration ttlOnCreate) {
            long next = ((Number) store.getOrDefault(key, 0L)).longValue() + 1L;
            store.put(key, next);
            return next;
        }
    }
}
