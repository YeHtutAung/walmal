package com.walmal.auth.application.impl;

import com.walmal.auth.api.dto.CreateUserRequest;
import com.walmal.auth.api.dto.LoginRequest;
import com.walmal.auth.api.dto.RegisterRequest;
import com.walmal.auth.api.dto.TokenResponse;
import com.walmal.auth.api.dto.UpdateUserRequest;
import com.walmal.auth.api.dto.UserProfileResponse;
import com.walmal.auth.application.AuthService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.walmal.auth.domain.RefreshTokenRecord;
import com.walmal.auth.domain.Role;
import com.walmal.auth.domain.User;
import com.walmal.auth.domain.event.UserDeactivatedEvent;
import com.walmal.auth.domain.event.UserRegisteredEvent;
import com.walmal.auth.infrastructure.JwtTokenProvider;
import com.walmal.auth.infrastructure.RefreshTokenAdapter;
import com.walmal.auth.infrastructure.UserRepository;
import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Core authentication service implementation.
 *
 * <p>Architecture contracts enforced here:</p>
 * <ul>
 *   <li>DIP: depends on JwtTokenProvider interface, CacheService via RefreshTokenAdapter,
 *       AuditService interface — no concrete infrastructure classes imported.</li>
 *   <li>Audit: deactivateUser writes audit_log BEFORE the repository save.</li>
 *   <li>Events: published via DomainEventPublisher (no direct RabbitTemplate calls).</li>
 * </ul>
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final long ACCESS_TOKEN_EXPIRE_SECONDS = 15 * 60L;
    private static final long REFRESH_TOKEN_EXPIRE_DAYS = 7L;

    private final UserRepository userRepository;
    private final RefreshTokenAdapter refreshTokenAdapter;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final DomainEventPublisher eventPublisher;

    /**
     * Pre-computed BCrypt hash used for constant-time dummy checks during login.
     * Prevents user enumeration via timing side-channel: without this, a missing
     * username returns in ~6 ms while an existing username takes ~260 ms (bcrypt).
     * Computed once at bean construction so the cost factor always matches runtime.
     */
    private final String dummyHash;

    public AuthServiceImpl(
            UserRepository userRepository,
            RefreshTokenAdapter refreshTokenAdapter,
            JwtTokenProvider jwtTokenProvider,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            DomainEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.refreshTokenAdapter = refreshTokenAdapter;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.dummyHash = passwordEncoder.encode("walmal-dummy-probe-do-not-use");
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username()).orElse(null);

        if (user == null) {
            // SECURITY: Run a dummy bcrypt check against a pre-computed hash so that
            // the response time for a non-existent username (~260 ms) is indistinguishable
            // from an existing username with a wrong password, preventing timing-based
            // user enumeration (M1).
            passwordEncoder.matches(request.password(), dummyHash);
            throw new BusinessRuleException("Invalid credentials");
        }

        if (!user.isActive()) {
            throw new BusinessRuleException("Account is deactivated: " + request.username());
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessRuleException("Invalid credentials");
        }

        return issueTokenPair(user);
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessRuleException("Username already taken: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessRuleException("Email already registered: " + request.email());
        }

        // SECURITY: Self-registration always assigns CUSTOMER role.
        // Privileged roles (ADMIN, STAFF, etc.) require admin-created accounts.
        Role role = Role.CUSTOMER;
        String hash = passwordEncoder.encode(request.password());

        User user = new User(request.username(), request.email(), hash, role);
        user = userRepository.save(user);

        log.info("New user registered: {} ({})", user.getUsername(), user.getId());

        eventPublisher.publish(
                new UserRegisteredEvent(user.getId(), user.getUsername(), user.getEmail(), role.name()),
                "auth.user.registered");

        return issueTokenPair(user);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Override
    public void logout(String refreshToken, UUID userId) {
        // Refresh token format: "{userId}:{tokenId}" — extract tokenId only
        if (refreshToken == null || !refreshToken.contains(":")) {
            log.debug("Logout called with unrecognised refresh token format for user {}", userId);
            return;
        }
        String[] parts = refreshToken.split(":", 2);
        try {
            UUID tokenId = UUID.fromString(parts[1]);
            refreshTokenAdapter.delete(userId, tokenId);
        } catch (IllegalArgumentException e) {
            log.debug("Logout called with non-UUID tokenId for user {}", userId);
        }
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        // Refresh token format issued by issueTokenPair: "{userId}:{tokenId}"
        // Both parts are UUIDs. The compound format allows O(1) Redis key lookup
        // without scanning all keys. The client stores and presents the full string.
        if (refreshToken == null || !refreshToken.contains(":")) {
            throw new BusinessRuleException("Invalid refresh token");
        }

        String[] parts = refreshToken.split(":", 2);
        if (parts.length != 2) {
            throw new BusinessRuleException("Invalid refresh token");
        }

        UUID userId;
        UUID tokenId;
        try {
            userId = UUID.fromString(parts[0]);
            tokenId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Invalid refresh token");
        }

        RefreshTokenRecord record = refreshTokenAdapter.find(userId, tokenId)
                .orElseThrow(() -> new BusinessRuleException("Refresh token not found or expired"));

        if (record.expiresAt().isBefore(Instant.now())) {
            refreshTokenAdapter.delete(userId, tokenId);
            throw new BusinessRuleException("Refresh token expired");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (!user.isActive()) {
            refreshTokenAdapter.delete(userId, tokenId);
            throw new BusinessRuleException("Account is deactivated");
        }

        // Rolling refresh: delete old token, issue new pair
        refreshTokenAdapter.delete(userId, tokenId);
        return issueTokenPair(user);
    }

    // ── Deactivate ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deactivateUser(UUID userId, String performedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // AUDIT FIRST — architecture rule: write audit_log before destructive operation
        auditService.log(new AuditEntry(
                "auth_users",
                userId,
                AuditAction.STATUS_CHANGE,
                "is_active=true",
                "is_active=false",
                performedBy));

        user.setActive(false);
        userRepository.save(user);

        // Revoke all active sessions immediately
        refreshTokenAdapter.deleteAllForUser(userId);

        log.info("User deactivated: {} by {}", userId, performedBy);

        eventPublisher.publish(
                new UserDeactivatedEvent(userId, user.getUsername(), performedBy),
                "auth.user.deactivated");
    }

    // ── Get current user ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        return toProfile(user);
    }

    // ── Create user (admin-only) ───────────────────────────────────────────────

    @Override
    @Transactional
    public UserProfileResponse createUser(CreateUserRequest request, String performedBy) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessRuleException("Username already taken: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessRuleException("Email already registered: " + request.email());
        }

        Role role = parseRole(request.role());

        String hash = passwordEncoder.encode(request.password());
        User user = new User(request.username(), request.email(), hash, role);
        if (Boolean.FALSE.equals(request.active())) {
            user.setActive(false);
        }
        user = userRepository.save(user);

        log.info("User created by admin {}: {} (role={})", performedBy, user.getUsername(), role);

        eventPublisher.publish(
                new UserRegisteredEvent(user.getId(), user.getUsername(), user.getEmail(), role.name()),
                "auth.user.registered");

        return toProfile(user);
    }

    // ── List users ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listUsers(String roleFilter, Boolean active, Pageable pageable) {
        Page<User> users;
        if (roleFilter != null && active != null) {
            users = userRepository.findByRoleAndIsActive(parseRole(roleFilter), active, pageable);
        } else if (roleFilter != null) {
            users = userRepository.findByRole(parseRole(roleFilter), pageable);
        } else if (active != null) {
            users = userRepository.findByIsActive(active, pageable);
        } else {
            users = userRepository.findAll(pageable);
        }
        return users.map(this::toProfile);
    }

    // ── Search users ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Passes the trimmed RAW query to the derived {@code ContainingIgnoreCase}
     * finder: Spring Data lowercases both sides for the comparison and escapes
     * LIKE wildcards ({@code %}, {@code _}, {@code \}) in the bound value itself,
     * so pre-lowercasing or manual escaping here would be redundant (and manual
     * escaping would break matches by double-escaping).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> searchUsers(String q, Pageable pageable) {
        if (q == null || q.trim().length() < 2) {
            return Page.empty(pageable);
        }
        String needle = q.trim();
        return userRepository
                .findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(needle, needle, pageable)
                .map(this::toProfile);
    }

    // ── Get user by ID ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return toProfile(user);
    }

    // ── Update user ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public UserProfileResponse updateUser(UUID userId, UpdateUserRequest request, String performedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Role newRole = request.role() != null ? parseRole(request.role()) : null;

        // Build audit old/new values before any mutation.
        // audit_log.old_value/new_value are jsonb columns — values MUST be valid JSON.
        StringBuilder oldVal = new StringBuilder();
        StringBuilder newVal = new StringBuilder();
        if (newRole != null) {
            oldVal.append("\"role\":\"").append(user.getRole().name()).append('"');
            newVal.append("\"role\":\"").append(newRole.name()).append('"');
        }
        if (request.active() != null) {
            if (!oldVal.isEmpty()) { oldVal.append(','); newVal.append(','); }
            oldVal.append("\"is_active\":").append(user.isActive());
            newVal.append("\"is_active\":").append(request.active());
        }

        if (oldVal.isEmpty()) {
            return toProfile(user);
        }
        oldVal.insert(0, '{').append('}');
        newVal.insert(0, '{').append('}');

        // AUDIT FIRST — architecture rule: write audit_log before destructive operation
        auditService.log(new AuditEntry(
                "auth_users",
                userId,
                AuditAction.UPDATE,
                oldVal.toString(),
                newVal.toString(),
                performedBy));

        if (newRole != null) {
            user.setRole(newRole);
        }
        if (request.active() != null) {
            user.setActive(request.active());
        }

        user = userRepository.save(user);
        log.info("User updated by {}: {} ({}→{})", performedBy, userId, oldVal, newVal);
        return toProfile(user);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private UserProfileResponse toProfile(User user) {
        return new UserProfileResponse(
                user.getId(), user.getUsername(), user.getEmail(),
                user.getRole().name(), user.isActive());
    }

    private Role parseRole(String roleStr) {
        try {
            return Role.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Invalid role: " + roleStr);
        }
    }

    /**
     * Issues a new access + refresh token pair and stores the refresh record in Redis.
     * Refresh token format returned to client: "{userId}:{tokenId}".
     */
    private TokenResponse issueTokenPair(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);

        UUID tokenId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(REFRESH_TOKEN_EXPIRE_DAYS, ChronoUnit.DAYS);

        RefreshTokenRecord record = new RefreshTokenRecord(
                user.getId(), tokenId, now, expiresAt);
        refreshTokenAdapter.store(user.getId(), tokenId, record);

        String opaqueRefreshToken = user.getId() + ":" + tokenId;

        return TokenResponse.bearer(
                accessToken,
                opaqueRefreshToken,
                ACCESS_TOKEN_EXPIRE_SECONDS,
                user.getRole().name());
    }

}
