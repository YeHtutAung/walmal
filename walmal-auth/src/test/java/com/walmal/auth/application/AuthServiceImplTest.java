package com.walmal.auth.application;

import com.walmal.auth.api.dto.CreateUserRequest;
import com.walmal.auth.api.dto.LoginRequest;
import com.walmal.auth.api.dto.RegisterRequest;
import com.walmal.auth.api.dto.TokenResponse;
import com.walmal.auth.api.dto.UserProfileResponse;
import com.walmal.auth.application.impl.AuthServiceImpl;
import com.walmal.auth.domain.RefreshTokenRecord;
import com.walmal.auth.domain.Role;
import com.walmal.auth.domain.User;
import com.walmal.auth.infrastructure.JwtTokenProvider;
import com.walmal.auth.infrastructure.RefreshTokenAdapter;
import com.walmal.auth.infrastructure.UserRepository;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthServiceImpl}.
 * All infrastructure dependencies are mocked with Mockito.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenAdapter refreshTokenAdapter;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private AuditService auditService;
    @Mock
    private DomainEventPublisher eventPublisher;

    private PasswordEncoder passwordEncoder;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4); // low strength for test speed
        authService = new AuthServiceImpl(
                userRepository,
                refreshTokenAdapter,
                jwtTokenProvider,
                passwordEncoder,
                auditService,
                eventPublisher);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnTokenResponse_when_loginCredentialsAreValid")
    void should_returnTokenResponse_when_loginCredentialsAreValid() {
        String raw = "password123";
        User user = buildUser(Role.CUSTOMER, raw);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(user)).thenReturn("access.token");

        TokenResponse response = authService.login(new LoginRequest("alice", raw));

        assertThat(response.accessToken()).isEqualTo("access.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.role()).isEqualTo("CUSTOMER");
        verify(refreshTokenAdapter).store(eq(user.getId()), any(UUID.class), any(RefreshTokenRecord.class));
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_userIsInactive")
    void should_throwBusinessRuleException_when_userIsInactive() {
        User user = buildUser(Role.CUSTOMER, "password123");
        user.setActive(false);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "password123")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_usernameDoesNotExist")
    void should_throwResourceNotFoundException_when_usernameDoesNotExist() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "pass")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User");
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_publishUserRegisteredEvent_when_registrationSucceeds")
    void should_publishUserRegisteredEvent_when_registrationSucceeds() {
        when(userRepository.existsByUsername("bob")).thenReturn(false);
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);

        User savedUser = buildUser(Role.CUSTOMER, "password123");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtTokenProvider.generateAccessToken(savedUser)).thenReturn("new.token");

        authService.register(new RegisterRequest("bob", "bob@example.com", "password123", null));

        verify(eventPublisher).publish(any(DomainEvent.class), eq("auth.user.registered"));
    }

    @Test
    @DisplayName("should_assignCustomerRole_when_registerRequestContainsAdminRole")
    void should_assignCustomerRole_when_registerRequestContainsAdminRole() {
        when(userRepository.existsByUsername("evil")).thenReturn(false);
        when(userRepository.existsByEmail("evil@example.com")).thenReturn(false);

        User savedUser = buildUser(Role.CUSTOMER, "password123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            assertThat(u.getRole()).isEqualTo(Role.CUSTOMER);
            // Set ID via reflection for token generation
            try {
                var idField = com.walmal.common.model.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(u, UUID.randomUUID());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return u;
        });
        when(jwtTokenProvider.generateAccessToken(any(User.class))).thenReturn("token");

        TokenResponse response = authService.register(
                new RegisterRequest("evil", "evil@example.com", "password123", "ADMIN"));

        assertThat(response.role()).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_usernameAlreadyTaken")
    void should_throwBusinessRuleException_when_usernameAlreadyTaken() {
        when(userRepository.existsByUsername("bob")).thenReturn(true);

        assertThatThrownBy(() ->
                authService.register(new RegisterRequest("bob", "bob@example.com", "password123", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Username already taken");
    }

    // ── Deactivate — audit BEFORE repository save ─────────────────────────────

    @Test
    @DisplayName("should_callAuditBeforeRepositorySave_when_deactivatingUser")
    void should_callAuditBeforeRepositorySave_when_deactivatingUser() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(Role.STAFF, "password123");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        InOrder order = inOrder(auditService, userRepository);

        authService.deactivateUser(userId, "admin");

        order.verify(auditService).log(any(AuditEntry.class));
        order.verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_deactivatingNonExistentUser")
    void should_throwResourceNotFoundException_when_deactivatingNonExistentUser() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.deactivateUser(userId, "admin"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(auditService, never()).log(any());
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throwBusinessRuleException_when_refreshTokenNotFoundInRedis")
    void should_throwBusinessRuleException_when_refreshTokenNotFoundInRedis() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String refreshToken = userId + ":" + tokenId;

        when(refreshTokenAdapter.find(userId, tokenId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not found or expired");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_refreshTokenIsExpired")
    void should_throwBusinessRuleException_when_refreshTokenIsExpired() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String refreshToken = userId + ":" + tokenId;

        RefreshTokenRecord expiredRecord = new RefreshTokenRecord(
                userId, tokenId,
                Instant.now().minus(10, ChronoUnit.DAYS),
                Instant.now().minus(3, ChronoUnit.DAYS)  // expiresAt in the past
        );

        when(refreshTokenAdapter.find(userId, tokenId)).thenReturn(Optional.of(expiredRecord));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("should_issueNewTokensAndDeleteOldKey_when_refreshTokenIsValid")
    void should_issueNewTokensAndDeleteOldKey_when_refreshTokenIsValid() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String refreshToken = userId + ":" + tokenId;

        RefreshTokenRecord validRecord = new RefreshTokenRecord(
                userId, tokenId,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(6, ChronoUnit.DAYS)
        );

        User user = buildUser(Role.CUSTOMER, "password123");
        when(refreshTokenAdapter.find(userId, tokenId)).thenReturn(Optional.of(validRecord));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(user)).thenReturn("new.access.token");

        TokenResponse response = authService.refresh(refreshToken);

        assertThat(response.accessToken()).isEqualTo("new.access.token");
        verify(refreshTokenAdapter).delete(userId, tokenId);
        verify(refreshTokenAdapter).store(eq(user.getId()), any(UUID.class), any(RefreshTokenRecord.class));
    }

    // ── Create user (admin-only) ──────────────────────────────────────────────

    @Test
    @DisplayName("should_returnUserProfile_when_createUserWithValidRole")
    void should_returnUserProfile_when_createUserWithValidRole() {
        when(userRepository.existsByUsername("staff1")).thenReturn(false);
        when(userRepository.existsByEmail("staff1@walmal.com")).thenReturn(false);

        User savedUser = buildUser(Role.STAFF, "password123");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        UserProfileResponse profile = authService.createUser(
                new CreateUserRequest("staff1", "staff1@walmal.com", "password123", "STAFF"), "admin");

        assertThat(profile.role()).isEqualTo("STAFF");
        assertThat(profile.isActive()).isTrue();
        verify(eventPublisher).publish(any(DomainEvent.class), eq("auth.user.registered"));
    }

    @Test
    @DisplayName("should_acceptAnyValidRole_when_createUser")
    void should_acceptAnyValidRole_when_createUser() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        User savedUser = buildUser(Role.ADMIN, "password123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            assertThat(u.getRole()).isEqualTo(Role.ADMIN);
            return savedUser;
        });

        UserProfileResponse profile = authService.createUser(
                new CreateUserRequest("admin2", "admin2@walmal.com", "password123", "ADMIN"), "admin");

        assertThat(profile.role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_createUserWithInvalidRole")
    void should_throwBusinessRuleException_when_createUserWithInvalidRole() {
        when(userRepository.existsByUsername("staff1")).thenReturn(false);
        when(userRepository.existsByEmail("staff1@walmal.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.createUser(
                new CreateUserRequest("staff1", "staff1@walmal.com", "password123", "SUPERADMIN"), "admin"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Invalid role");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_createUserWithDuplicateUsername")
    void should_throwBusinessRuleException_when_createUserWithDuplicateUsername() {
        when(userRepository.existsByUsername("existing")).thenReturn(true);

        assertThatThrownBy(() -> authService.createUser(
                new CreateUserRequest("existing", "new@walmal.com", "password123", "STAFF"), "admin"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Username already taken");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User buildUser(Role role, String rawPassword) {
        User user = new User("alice", "alice@example.com", passwordEncoder.encode(rawPassword), role);
        // Inject an id via reflection since BaseEntity.id is set by JPA normally
        try {
            var idField = com.walmal.common.model.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException("Could not set user id for test", e);
        }
        return user;
    }
}
