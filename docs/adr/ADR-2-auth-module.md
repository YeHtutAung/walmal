# ADR-2: Authentication Module Design

**Date**: 2026-05-20
**Status**: Accepted
**Module**: walmal-auth (Build Order Step 2)
**Authors**: Backend Architect Agent

---

## Context

walmal requires a unified identity and access control layer across all channels:
web storefront, backoffice, POS terminals, and warehouse operations. The module
must support four roles with distinct access levels, operate statelessly at the
HTTP level, and integrate cleanly with the modular monolith's module boundary rules.

This ADR records the decisions made for the auth module's JWT strategy, token
storage, database schema, service interface design, and RabbitMQ event contracts.

---

## Decision Drivers

1. Stateless HTTP tier — no server-side session for horizontal scaling readiness.
2. Fast token validation — downstream modules must validate the caller's identity
   on every request without a DB query.
3. Revocable sessions — logout must invalidate a session within a bounded time window.
4. Module boundary compliance — no other module may import auth's Repository beans.
5. DIP compliance — all infrastructure (Redis, RabbitMQ) accessed through common interfaces.
6. Audit trail — user deactivation (destructive UPDATE) writes to audit_log first.

---

## Considered Options

### Option A: Session-based authentication (Spring Session + Redis)

- Server stores full session in Redis; no JWT.
- Pros: immediate revocation, standard Spring pattern.
- Cons: every request requires a Redis lookup; tighter coupling between web and POS
  layers; harder to use in offline POS context.
- **Rejected**: Offline POS terminals cannot reach Redis; JWT validation is purely
  in-memory once the access token is issued.

### Option B: JWT access + JWT refresh (all JWT)

- Both access and refresh tokens are JWTs signed with the same or different secrets.
- Pros: no Redis dependency for refresh validation.
- Cons: JWT refresh tokens cannot be revoked without a blacklist. A stolen refresh
  token is valid for 7 days with no revocation path. All active refresh tokens must
  be blacklisted on logout, which requires a DB or Redis store anyway.
- **Rejected**: The alleged benefit (no Redis for refresh) disappears once revocation
  is added. Opaque refresh tokens with Redis TTL are simpler and more secure.

### Option C: JWT access (stateless) + opaque refresh token (Redis) [SELECTED]

- Access tokens: HS256 JWT, 15-minute TTL, validated in-memory.
- Refresh tokens: UUID, stored in Redis under `auth:refresh:{userId}:{tokenId}`,
  7-day TTL with rolling refresh.
- Logout: delete the Redis key — O(1) revocation.
- Pros: access token validation requires no I/O; refresh token revocation is immediate;
  offline POS can cache the access token for its 15-minute window without Redis access.
- **Accepted**.

### Option D: Spring Security OAuth2 Authorization Server

- Full OAuth2/OIDC implementation with authorization codes, client registrations, etc.
- Pros: industry standard, supports third-party integrations.
- Cons: significant complexity overhead for an MVP internal platform with no third-party
  clients in scope. Can be adopted later by replacing the JWT issuance layer.
- **Rejected for MVP**. The `TokenValidationService` interface is designed to be
  compatible with a future OAuth2 migration — only the implementation changes.

### Option E: auth_refresh_tokens database table instead of Redis

- Persist refresh tokens in PostgreSQL. Redis is not required for token revocation.
- Pros: durable across Redis restarts; full SQL audit of token issuance.
- Cons: every /refresh call writes a row; requires a background cleanup job for
  expired tokens; adds DB write latency to a high-frequency path.
- **Rejected**: Refresh tokens are session artifacts, not business records. Automatic
  Redis TTL enforcement eliminates cleanup jobs. Redis AOF persistence mitigates the
  durability concern. A future DB-backed adapter can be added without changing the
  AuthService interface.

---

## Decision

### JWT Strategy

- Access token: HS256 signed JWT, 15-minute TTL.
  Claims: sub (UUID string), role (string), username, iat, exp, jti (UUID, reserved
  for future blacklist support).
- Refresh token: UUID opaque token, 7-day TTL, stored in Redis.
  Key pattern: `auth:refresh:{userId}:{tokenId}`
  Value: JSON-serialised RefreshTokenRecord (userId, tokenId, issuedAt, expiresAt).
- Rolling refresh: on every /refresh call, the old Redis key is deleted and a new
  key is written. The old tokenId is never reusable.

### Token Revocation

- Logout: Redis key deletion. Access token expires naturally within 15 minutes.
- Account lock: is_active = false in auth_users. Checked on every /refresh and /login.
  Existing access tokens remain valid for up to 15 minutes after the lock is applied.
  This is the accepted revocation lag for MVP.

### Refresh Token Storage

- Redis only. No auth_refresh_tokens database table.
- Redis key: `auth:refresh:{userId}:{tokenId}` — structured to allow
  `evictByPrefix("auth:refresh:{userId}:")` to revoke all sessions for one user
  (e.g. forced logout on password change or account lock).

### Interface Segregation (ISP)

| Interface | Consumer | Exposed Methods |
|---|---|---|
| AuthService | AuthController only (same module) | login, register, logout, refresh, deactivateUser |
| TokenValidationService | All downstream modules | isValid, extractUserId, extractRole, extractUsername |
| UserLookupService | Order, POS modules | findUsernameById, findRoleById, isUserActive |

### Password Hashing

- BCrypt strength 12. Spring Security BCryptPasswordEncoder.
- BCryptPasswordEncoder bean is declared in AuthSecurityConfig. It is never injected
  outside the auth module.

### Cross-Module Principal

- AuthenticatedPrincipal record added to walmal-common:
  `record AuthenticatedPrincipal(UUID userId, String username, String role)`.
- Role is String in the common record to avoid walmal-common depending on walmal-auth.
- JwtAuthenticationFilter populates SecurityContextHolder with an
  UsernamePasswordAuthenticationToken whose principal is AuthenticatedPrincipal.
- Downstream controllers use @AuthenticationPrincipal AuthenticatedPrincipal to
  receive the current user without any auth module import.

---

## Owned Tables

| Table | Owner | Description |
|---|---|---|
| auth_users | walmal-auth | User identity, role, password hash, active status |

No other tables. Refresh token state is Redis-only.

Flyway migration: V2__auth_create_tables.sql

```sql
CREATE TABLE auth_users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    role          VARCHAR(20)  NOT NULL
                  CHECK (role IN ('ADMIN', 'STAFF', 'CASHIER', 'CUSTOMER')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_auth_users_username ON auth_users (username);
CREATE UNIQUE INDEX idx_auth_users_email    ON auth_users (email);
CREATE INDEX        idx_auth_users_role     ON auth_users (role);
CREATE INDEX        idx_auth_users_active   ON auth_users (is_active);
```

---

## Published Events

| Routing Key | Exchange | Trigger | Consumer Intent |
|---|---|---|---|
| auth.user.registered | auth.exchange | register() success | Notification module: send welcome email |
| auth.user.deactivated | auth.exchange | deactivateUser() by ADMIN | Notification module: notify user; Order module: cancel pending orders |

No events for login or logout. These are high-frequency operations; audit_log is
the correct sink for login/logout audit trail if required.

### Event Classes

```
com.walmal.auth.domain.event.UserRegisteredEvent  extends DomainEvent
  Fields: UUID userId, String username, String email, String role

com.walmal.auth.domain.event.UserDeactivatedEvent extends DomainEvent
  Fields: UUID userId, String username, String performedBy
```

---

## Audit Log Compliance

All destructive DB operations write to audit_log before execution (CLAUDE.md rule).

| Operation | AuditAction | Table | Trigger Point |
|---|---|---|---|
| deactivateUser | STATUS_CHANGE | auth_users | Before UPDATE is_active = false |
| Hard delete (admin, future) | DELETE | auth_users | Before DELETE statement |

Logout (Redis key deletion) is not a DB operation and does not require an audit_log entry.

---

## Security Configuration

```
AuthSecurityConfig declares:
  SecurityFilterChain:
    csrf: DISABLED (stateless JWT API)
    sessionManagement: STATELESS
    cors: CorsConfigurationSource bean
    exceptionHandling:
      authenticationEntryPoint -> 401 JSON (no redirect)
      accessDeniedHandler -> 403 JSON (no redirect)
    authorizeHttpRequests:
      PUBLIC:
        POST /api/v1/auth/login
        POST /api/v1/auth/register
        POST /api/v1/auth/refresh
        GET  /actuator/health
        GET  /v3/api-docs/**
        GET  /swagger-ui/**
      ADMIN role only:
        POST /api/v1/auth/users/{id}/deactivate
      Any authenticated role:
        POST /api/v1/auth/logout
        GET  /api/v1/auth/me
      All others: authenticated
    addFilterBefore: JwtAuthenticationFilter before UsernamePasswordAuthenticationFilter

JwtAuthenticationFilter (OncePerRequestFilter):
  1. Extract Bearer token from Authorization header
  2. Call TokenValidationService.isValid(token) — no Redis I/O
  3. If valid: extract claims, build AuthenticatedPrincipal, set SecurityContextHolder
  4. If invalid: do not set SecurityContext; proceed to next filter
```

---

## Package Structure

```
walmal-auth/src/main/java/com/walmal/auth/
  api/
    AuthController.java
    dto/
      LoginRequest.java
      RegisterRequest.java
      TokenResponse.java
      RefreshTokenRequest.java
      UserProfileResponse.java
  domain/
    User.java                        (@Entity, table: auth_users)
    Role.java                        (enum: ADMIN, STAFF, CASHIER, CUSTOMER)
    RefreshTokenRecord.java          (record, Redis value object)
    event/
      UserRegisteredEvent.java       (extends DomainEvent)
      UserDeactivatedEvent.java      (extends DomainEvent)
  application/
    AuthService.java                 (interface — internal)
    TokenValidationService.java      (interface — cross-module)
    UserLookupService.java           (interface — cross-module)
    impl/
      AuthServiceImpl.java
      TokenValidationServiceImpl.java
      UserLookupServiceImpl.java
  infrastructure/
    UserRepository.java              (JpaRepository<User, UUID>)
    RefreshTokenAdapter.java         (wraps CacheService)
    JwtTokenProvider.java            (interface)
    JjwtTokenProviderImpl.java       (JJWT implementation)
  config/
    AuthSecurityConfig.java
    JwtAuthenticationFilter.java
    JwtProperties.java               (@ConfigurationProperties("walmal.jwt"))
    AuthRabbitMQConfig.java
```

---

## Maven Dependency Declaration

```xml
<dependency>
    <groupId>com.walmal</groupId>
    <artifactId>walmal-auth</artifactId>
    <version>${project.version}</version>
</dependency>
```

Direct dependencies of walmal-auth:
- walmal-common
- spring-boot-starter-security
- spring-boot-starter-web
- spring-boot-starter-data-jpa
- io.jsonwebtoken:jjwt-api:0.12.x (+ jjwt-impl, jjwt-jackson at runtime)
- springdoc-openapi-starter-webmvc-ui (version aligned with walmal-app)
- spring-boot-starter-test + testcontainers:postgresql at test scope

walmal-infrastructure is NOT a direct dependency of walmal-auth. Auth depends on
walmal-common interfaces only. The application assembly (walmal-app) wires the
concrete infrastructure implementations via Spring auto-configuration.

---

## Consequences

### Positive

- Downstream modules validate requests with zero I/O (in-memory JWT parsing).
- Offline POS terminals can cache the access token and operate for up to 15 minutes
  without Redis connectivity — aligned with the offline POS sync business rule.
- Module boundary is clean: no other module imports UserRepository or JjwtTokenProviderImpl.
- TokenValidationService and UserLookupService interfaces are stable and survive a
  future OAuth2 migration in the implementation layer.
- Rolling refresh tokens prevent replay attacks on stolen refresh tokens.

### Negative / Risks

- 15-minute access token revocation lag: a stolen access token is valid until expiry.
  Mitigated by short TTL and HTTPS-only deployment requirement.
- Redis dependency for session state: Redis outage forces re-login for all active users.
  Mitigated by Redis AOF persistence configured in Docker Compose.
- HS256 single-secret signing: secret rotation invalidates all active access tokens.
  Mitigated by a planned dual-key rotation window implementable in JjwtTokenProviderImpl
  without changing the TokenValidationService interface.
- BCrypt at strength 12 adds ~300ms to login response time. Acceptable for an
  authentication endpoint that is not on the hot path.

---

## Definition of Done Checklist

- [ ] AuthService interface defined in application/
- [ ] TokenValidationService interface defined in application/
- [ ] UserLookupService interface defined in application/
- [ ] AuthServiceImpl, TokenValidationServiceImpl, UserLookupServiceImpl complete
- [ ] JwtAuthenticationFilter wired in AuthSecurityConfig
- [ ] V2__auth_create_tables.sql Flyway migration applied
- [ ] Integration tests pass (AuthIntegrationTest with Testcontainers PostgreSQL + Redis)
- [ ] @WebMvcTest for AuthController with MockMvc
- [ ] No cross-module Repository bean imports
- [ ] OpenAPI annotations on AuthController (springdoc)
- [ ] Docker Compose health check passes with auth endpoints reachable
- [ ] security-auditor agent review completed
