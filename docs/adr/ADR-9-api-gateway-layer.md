# ADR-9: API Gateway Layer

**Date**: 2026-05-21
**Status**: Accepted
**Module**: walmal-app (cross-cutting layer, Build Order Step 9)
**Authors**: Backend Architect Agent

---

## Context

With all eight bounded-context modules complete (auth, product, inventory, order,
POS, warehouse, notification, plus infrastructure/common), the platform needs a
unified cross-cutting layer that provides:

- Consistent error responses for exceptions not caught by module-scoped handlers
- Rate limiting to protect endpoints from abuse
- Request correlation for observability and debugging
- Expanded health and metrics endpoints for production readiness
- An explicit API versioning decision record

This is NOT a new Maven module. All components live inside `walmal-app`, which is
the application assembly module. The gateway layer is implemented as servlet
filters and a global exception handler that wrap the existing module controllers.

### What Already Exists

The following cross-cutting concerns are already implemented and do not need to
be duplicated or replaced:

1. **JWT Authentication** -- `JwtAuthenticationFilter` in walmal-auth extracts
   and validates Bearer tokens on every request. Wired before
   `UsernamePasswordAuthenticationFilter` in `AuthSecurityConfig`.
2. **RBAC** -- 49 `@PreAuthorize` annotations across all module controllers.
   Roles: ADMIN, STAFF, WAREHOUSE_MANAGER, WAREHOUSE_STAFF, POS_OPERATOR.
3. **Module-scoped exception handlers** -- Each module has a
   `@RestControllerAdvice(basePackages = "com.walmal.{module}.api")` that
   handles `ResourceNotFoundException`, `BusinessRuleException`,
   `ConcurrencyConflictException`, `MethodArgumentNotValidException`, and
   `IllegalArgumentException`.
4. **CORS** -- Configured via `CorsConfigurationSource` bean in
   `AuthSecurityConfig`.
5. **Stateless sessions** -- `SessionCreationPolicy.STATELESS`, CSRF disabled.
6. **OpenAPI** -- Per-module `GroupedOpenApi` beans with springdoc paths
   configured in `application.yml`.

### What This ADR Adds

The gateway layer fills the gaps not covered by module-level infrastructure:

| Concern | Current State | Gateway Addition |
|---|---|---|
| Unhandled exceptions | Fall through to Spring Boot default error page | Global exception handler with `ApiResponse` format |
| Authentication/authorization errors | Custom 401/403 in `AuthSecurityConfig` (non-`ApiResponse` format) | Global handler provides consistent `ApiResponse` envelope |
| Rate limiting | None | Sliding window filter with Redis backing |
| Request correlation | None | Correlation ID filter with MDC integration |
| Actuator exposure | `health,info` only | Expand to include `metrics,prometheus` |

---

## Decision Drivers

1. **Consistency** -- Every HTTP response, including errors, must use the
   `ApiResponse` envelope defined in walmal-common.
2. **DIP compliance** -- Rate limiting must use the `CacheService` interface
   from walmal-common, never `RedisTemplate` directly.
3. **Observability** -- Every request must carry a correlation ID for
   distributed tracing readiness.
4. **Defense in depth** -- Rate limiting protects against brute-force attacks
   on auth endpoints and inventory/stock abuse.
5. **No new modules** -- The gateway is a cross-cutting layer in `walmal-app`,
   not a bounded context.

---

## Considered Options

### Rate Limiting Algorithm

#### Option A: Token Bucket

- Each user/IP has a bucket with N tokens; each request consumes one token;
  tokens refill at a fixed rate.
- Pros: smooth rate enforcement, handles bursts gracefully.
- Cons: requires atomic decrement-and-check plus a scheduled refill, which is
  more complex to implement correctly with `CacheService` (which exposes
  `get`/`put`/`evict` but no atomic increment).
- **Rejected**: `CacheService` does not expose atomic operations. Implementing
  token bucket correctly would require either extending the interface or
  bypassing it with direct Redis calls, violating DIP.

#### Option B: Sliding Window Counter [SELECTED]

- Each request increments a counter stored under a key that includes the
  current minute. The filter checks the sum of the current and previous
  minute's counters (weighted by elapsed fraction) against the limit.
- Simplified variant for MVP: count requests in the current 60-second window
  only. The window key includes a truncated timestamp so it naturally expires.
- Pros: simple to implement with `CacheService.get()` and `CacheService.put()`
  using TTL; no atomic operations required; deterministic memory usage.
- Cons: less smooth than token bucket at window boundaries (a user could
  theoretically send 100 requests at second 59 and 100 more at second 61).
- **Accepted**: The simplicity tradeoff is appropriate for MVP. The window
  boundary edge case is mitigated by the short window (60 seconds) and the
  fact that most abuse patterns are sustained, not boundary-timed.

### Rate Limiting State Storage

#### Option A: In-memory ConcurrentHashMap

- No Redis dependency. Each JVM instance maintains its own counters.
- **Rejected**: When walmal scales to multiple instances, each instance has
  independent counters, effectively multiplying the rate limit by the number
  of instances.

#### Option B: Redis via CacheService [SELECTED]

- Single shared counter across all application instances.
- **Accepted**: `CacheService` already abstracts Redis. Using it maintains
  DIP compliance and provides correct behavior in multi-instance deployments.

### Global Exception Handler Response Format

#### Option A: RFC 7807 ProblemDetail

- Some module handlers (product) already use `ProblemDetail`.
- **Rejected for global handler**: The majority of module handlers (order,
  inventory, POS, warehouse, notification) use `ApiResponse`. The global
  handler should use the dominant convention. A future ADR may standardize
  all module handlers on one format, but that is out of scope here.

#### Option B: ApiResponse envelope [SELECTED]

- Consistent with the `ApiResponse` record in walmal-common used by most
  module controllers and exception handlers.
- **Accepted**.

---

## Decision

### Package Structure

All gateway layer components are placed in a single package inside `walmal-app`:

```
walmal-app/src/main/java/com/walmal/gateway/
  filter/
    RequestLoggingFilter.java        (OncePerRequestFilter)
    RateLimitFilter.java             (OncePerRequestFilter)
  exception/
    GlobalExceptionHandler.java      (@RestControllerAdvice)
    RateLimitExceededException.java  (RuntimeException)
  config/
    GatewayFilterConfig.java         (@Configuration, filter registration)
    ActuatorConfig.java              (@Configuration, if needed beyond YAML)
```

---

### Component 1: Request Logging Filter

**Class**: `com.walmal.gateway.filter.RequestLoggingFilter`
**Extends**: `OncePerRequestFilter`
**Filter Order**: `Ordered.HIGHEST_PRECEDENCE + 1` (first custom filter in the chain)

Responsibilities:

1. Generate a UUID correlation ID for every inbound request.
2. Place the correlation ID in the SLF4J MDC under key `correlationId`.
3. Add the correlation ID to the response as header `X-Correlation-Id`.
4. Log at INFO level on request completion: HTTP method, URI, response status
   code, duration in milliseconds, and userId (from `SecurityContextHolder`,
   if authenticated; otherwise `anonymous`).
5. Clear the MDC entry in the `finally` block to prevent leaks in thread-pool
   reuse.

**Log format** (structured, parseable):

```
INFO  [correlationId=abc-123] POST /api/v1/orders 201 45ms userId=550e8400-...
INFO  [correlationId=def-456] GET /api/v1/products 200 12ms userId=anonymous
```

**Why first in the chain**: The correlation ID must be available to all
downstream filters (including `JwtAuthenticationFilter` and `RateLimitFilter`)
and all controller exception handlers so that error responses also carry the
correlation ID. However, because `SecurityContextHolder` is populated by
`JwtAuthenticationFilter` (which runs later), the userId portion of the log
entry is captured on the response path (after `filterChain.doFilter` returns),
not on the request path.

---

### Component 2: Rate Limit Filter

**Class**: `com.walmal.gateway.filter.RateLimitFilter`
**Extends**: `OncePerRequestFilter`
**Filter Order**: `Ordered.HIGHEST_PRECEDENCE + 10` (after RequestLoggingFilter,
after JwtAuthenticationFilter)

**Important ordering constraint**: This filter MUST execute after
`JwtAuthenticationFilter` so that `SecurityContextHolder` is populated when the
request carries a valid JWT. The filter reads the authentication context to
determine whether to apply per-user or per-IP limits.

#### Rate Limits

| Client Type | Limit | Window | Redis Key Pattern |
|---|---|---|---|
| Authenticated user | 100 requests | 60 seconds | `ratelimit:{userId}:{windowKey}` |
| Unauthenticated IP | 20 requests | 60 seconds | `ratelimit:ip:{ipAddress}:{windowKey}` |

Where `windowKey` = `System.currentTimeMillis() / 60000` (minutes since epoch,
truncated).

#### Algorithm (Sliding Window Counter -- Simplified)

```
1. Determine client identity:
   - If SecurityContextHolder contains an AuthenticatedPrincipal: use userId
   - Otherwise: use client IP address (from X-Forwarded-For header if present,
     falling back to request.getRemoteAddr())
2. Build Redis key: "ratelimit:{identity}:{windowKey}"
3. CacheService.get(key, Integer.class):
   - If absent: CacheService.put(key, 1, Duration.ofSeconds(90))
     (90-second TTL ensures the key outlives its window for the weighted
     calculation, but auto-expires)
   - If present and < limit: CacheService.put(key, count + 1, Duration.ofSeconds(90))
   - If present and >= limit: throw RateLimitExceededException
4. On RateLimitExceededException: respond immediately with HTTP 429,
   body: ApiResponse.error("Rate limit exceeded. Try again later.")
   Set response header: Retry-After: 60
```

Note on atomicity: `CacheService.get()` followed by `CacheService.put()` is
not atomic. In a high-concurrency scenario, a small number of requests above
the limit may slip through during the read-check-write window. This is
acceptable for MVP rate limiting, which is a best-effort abuse deterrent,
not a billing-grade metering system. If stricter guarantees are needed in the
future, a `CacheService.increment(key, ttl)` method can be added to the
interface with a Redis INCR-based implementation.

#### Excluded Paths

The following paths are excluded from rate limiting:

- `/actuator/**` -- health checks from infrastructure/load balancers
- `/v3/api-docs/**` -- OpenAPI spec retrieval
- `/swagger-ui/**` -- Swagger UI static assets

The filter's `shouldNotFilter()` method checks these prefixes.

---

### Component 3: Global Exception Handler

**Class**: `com.walmal.gateway.exception.GlobalExceptionHandler`
**Annotation**: `@RestControllerAdvice` with `@Order(Ordered.LOWEST_PRECEDENCE)`

This handler catches exceptions that escape module-scoped
`@RestControllerAdvice` handlers. The `@Order(LOWEST_PRECEDENCE)` ensures
module-specific handlers are consulted first.

#### Exception Mappings

| Exception | HTTP Status | Response Body |
|---|---|---|
| `RateLimitExceededException` | 429 Too Many Requests | `ApiResponse.error("Rate limit exceeded. Try again later.")` |
| `AccessDeniedException` (Spring Security) | 403 Forbidden | `ApiResponse.error("Access denied.")` |
| `AuthenticationException` (Spring Security) | 401 Unauthorized | `ApiResponse.error("Authentication required.")` |
| `HttpRequestMethodNotSupportedException` | 405 Method Not Allowed | `ApiResponse.error("HTTP method not supported: {method}")` |
| `NoHandlerFoundException` | 404 Not Found | `ApiResponse.error("No endpoint found for {method} {path}")` |
| `HttpMessageNotReadableException` | 400 Bad Request | `ApiResponse.error("Malformed request body.")` |
| `MethodArgumentNotValidException` | 400 Bad Request | `ApiResponse.error("Validation failed", [field errors])` |
| `ResourceNotFoundException` (walmal-common) | 404 Not Found | `ApiResponse.error(ex.getMessage())` |
| `BusinessRuleException` (walmal-common) | 409 Conflict | `ApiResponse.error(ex.getMessage())` |
| `ConcurrencyConflictException` (walmal-common) | 409 Conflict | `ApiResponse.error(ex.getMessage())` |
| `Exception` (catch-all) | 500 Internal Server Error | `ApiResponse.error("An unexpected error occurred.")` |

**Security note on the catch-all**: The 500 handler MUST NOT include
`ex.getMessage()` or stack trace details in the response body. Internal error
details are logged at ERROR level with the correlation ID, but the client
receives only the generic message. This prevents information leakage.

**Logging**: All 5xx responses are logged at ERROR level. All 4xx responses
are logged at WARN level. The correlation ID from MDC is included automatically
via the logging pattern.

#### Interaction with AuthSecurityConfig Exception Handling

`AuthSecurityConfig` currently defines inline `authenticationEntryPoint` and
`accessDeniedHandler` lambdas that return non-`ApiResponse` JSON. These fire
for Spring Security filter chain rejections (e.g., missing token on a protected
endpoint), which occur BEFORE the request reaches a controller. The
`GlobalExceptionHandler` cannot intercept these because they are not controller
exceptions.

**Decision**: The `AuthSecurityConfig` entry point and access denied handler
should be updated to return `ApiResponse` format for consistency. This is a
minor change to the existing lambdas (replacing the `Map.of(...)` response with
`ApiResponse.error(...)`) and does not alter the security behavior. The update
is part of the gateway layer implementation scope.

---

### Component 4: Filter Chain Configuration

**Class**: `com.walmal.gateway.config.GatewayFilterConfig`

Registers the two gateway filters with explicit ordering using
`FilterRegistrationBean`.

#### Complete Filter Chain Order (request path, top to bottom)

```
+-------------------------------------------------------+
|  1. RequestLoggingFilter                               |
|     Order: HIGHEST_PRECEDENCE + 1                      |
|     - Generate correlation ID                          |
|     - Set MDC + response header                        |
+-------------------------------------------------------+
         |
         v
+-------------------------------------------------------+
|  2. Spring Security Filter Chain                       |
|     (DelegatingFilterProxy -> SecurityFilterChain)      |
|     Internally includes:                               |
|       a. CorsFilter                                    |
|       b. JwtAuthenticationFilter                       |
|          (before UsernamePasswordAuthenticationFilter)  |
|       c. AuthorizationFilter (@PreAuthorize evaluation) |
|       d. ExceptionTranslationFilter                    |
+-------------------------------------------------------+
         |
         v
+-------------------------------------------------------+
|  3. RateLimitFilter                                    |
|     Order: HIGHEST_PRECEDENCE + 10                     |
|     - Read SecurityContext (populated by step 2b)      |
|     - Check rate limit via CacheService                |
|     - Reject with 429 if exceeded                      |
+-------------------------------------------------------+
         |
         v
+-------------------------------------------------------+
|  4. DispatcherServlet                                  |
|     - Route to @RestController                         |
|     - Module exception handlers (per-module @RCA)      |
|     - GlobalExceptionHandler (lowest precedence @RCA)  |
+-------------------------------------------------------+
```

**Note on filter ordering vs. Spring Security**: Spring Security's filter chain
is registered with `DEFAULT_ORDER` = -100 (from `SecurityProperties`). The
`RequestLoggingFilter` at `HIGHEST_PRECEDENCE + 1` runs before it. The
`RateLimitFilter` at `HIGHEST_PRECEDENCE + 10` must run AFTER the security
filter chain. Since `HIGHEST_PRECEDENCE + 10` is still a very low numeric
value (numerically less than -100), the `RateLimitFilter` registration must
use `FilterRegistrationBean` with an explicit order AFTER the security chain.

**Corrected ordering values for `FilterRegistrationBean`**:

| Filter | Order Value | Rationale |
|---|---|---|
| `RequestLoggingFilter` | `-110` | Before Spring Security (-100) |
| `RateLimitFilter` | `-90` | After Spring Security (-100), so SecurityContext is populated |

---

### Component 5: Actuator and Health Configuration

**Current state** in `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
  health:
    mail:
      enabled: false
```

**Updated configuration**:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
    prometheus:
      enabled: true
  health:
    mail:
      enabled: false
  metrics:
    tags:
      application: walmal
```

Changes:
- Add `metrics` and `prometheus` to the exposure list.
- Enable the Prometheus endpoint for future Grafana/Prometheus integration.
- Add application tag to all metrics for multi-app environments.

**Security**: Actuator endpoints (`/actuator/**`) are already permitted in
`AuthSecurityConfig` for GET on `/actuator/health`. The expanded endpoints
(`/actuator/metrics`, `/actuator/prometheus`) should be restricted to
authenticated users with ADMIN role in production. For MVP local development,
they are permitted without authentication. A future security hardening pass
should add:

```java
.requestMatchers(HttpMethod.GET, "/actuator/metrics/**", "/actuator/prometheus")
    .hasRole("ADMIN")
```

**Dependency**: The `micrometer-registry-prometheus` dependency must be added
to `walmal-app/pom.xml`:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

This is managed by the Spring Boot BOM, so no version is needed.

---

### Component 6: API Versioning

**Decision**: URI-based versioning with the `/api/v1/` prefix.

This is already implemented consistently across all module controllers:

| Module | Base Path |
|---|---|
| Auth | `/api/v1/auth` |
| Product | `/api/v1/products` |
| Inventory | `/api/v1/inventory` |
| Order | `/api/v1/orders` |
| POS | `/api/v1/pos` |
| Warehouse | `/api/v1/warehouse` |
| Notification | `/api/v1/notifications` |

**Rejected alternatives**:

- **Header-based versioning** (`Accept: application/vnd.walmal.v1+json`):
  more REST-pure but harder to test with browsers and curl. Rejected for
  MVP simplicity.
- **Query parameter versioning** (`?version=1`): non-standard, makes caching
  harder. Rejected.

**No additional configuration is required.** This decision is recorded here
as an explicit architectural choice so that future API version bumps (v2)
follow the same URI prefix pattern.

---

### Security Confirmation

The gateway layer confirms the following security posture without adding
redundant mechanisms:

| Concern | Implemented By | Gateway Layer Action |
|---|---|---|
| Authentication | `JwtAuthenticationFilter` (walmal-auth) | Confirmed. No changes. |
| Authorization | `@PreAuthorize` on all controllers | Confirmed. 49 annotations across all modules. |
| CSRF | Disabled in `AuthSecurityConfig` | Confirmed. Correct for stateless JWT API. |
| CORS | `CorsConfigurationSource` bean | Confirmed. No changes. |
| Session management | `STATELESS` policy | Confirmed. No changes. |
| Rate limiting | Not implemented | **Added** by `RateLimitFilter`. |
| Error information leakage | Inconsistent | **Fixed** by `GlobalExceptionHandler` (no stack traces in 5xx). |

The gateway layer adds defense-in-depth (rate limiting) and consistency
(error format) without duplicating or conflicting with existing security
mechanisms.

---

## Consequences

### Positive

- Every HTTP response, including unhandled exceptions, uses the `ApiResponse`
  envelope. Clients can rely on a single response schema for error handling.
- Rate limiting protects auth endpoints from brute-force attacks and
  inventory endpoints from stock-checking abuse, without requiring
  per-module implementation.
- Correlation IDs enable end-to-end request tracing across log aggregation
  systems. When a user reports an error, support can search by the
  `X-Correlation-Id` header value.
- Prometheus metrics endpoint enables future integration with Grafana
  dashboards without code changes.
- The gateway layer is entirely in `walmal-app` with no new modules, keeping
  the build dependency graph unchanged.
- DIP compliance is maintained: `RateLimitFilter` uses `CacheService`, not
  `RedisTemplate`.

### Negative / Risks

- **Rate limit race condition**: The `CacheService.get()` + `put()` sequence
  is not atomic. Under extreme concurrency, a small number of requests above
  the limit may pass through. Acceptable for MVP; mitigated by adding an
  `increment()` method to `CacheService` in a future iteration.
- **Sliding window boundary**: A client could send up to 2x the limit at
  window boundaries (100 at second 59, 100 at second 61). Mitigated by the
  short window duration. A weighted two-window calculation can be added later
  without changing the filter's public contract.
- **Module exception handler inconsistency**: Some modules (product) use
  `ProblemDetail` while others (order, inventory, POS, warehouse,
  notification) use `ApiResponse`. The global handler uses `ApiResponse` to
  match the majority. Standardizing all module handlers to `ApiResponse` is
  a recommended follow-up but is out of scope for this ADR.
- **Actuator security in production**: The expanded actuator endpoints
  (`metrics`, `prometheus`) are not role-restricted in MVP. This must be
  addressed before production deployment.

---

## Configuration Changes Summary

### application.yml (walmal-app)

```yaml
# Add to existing management section:
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
    prometheus:
      enabled: true
  health:
    mail:
      enabled: false
  metrics:
    tags:
      application: walmal

# Add to enable 404 exceptions reaching the global handler:
spring:
  mvc:
    throw-exception-if-no-handler-found: true
  web:
    resources:
      add-mappings: false
```

### pom.xml (walmal-app)

```xml
<!-- Add Prometheus metrics registry -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### AuthSecurityConfig (walmal-auth)

Update the `authenticationEntryPoint` and `accessDeniedHandler` lambdas to
return `ApiResponse` format instead of raw `Map.of(...)`. This is a minor
formatting change, not a security behavior change.

---

## Definition of Done Checklist

- [ ] `RequestLoggingFilter` implemented with correlation ID, MDC, response header
- [ ] `RateLimitFilter` implemented using `CacheService` interface
- [ ] `RateLimitExceededException` defined in gateway package
- [ ] `GlobalExceptionHandler` catches all specified exception types
- [ ] `GatewayFilterConfig` registers filters with correct ordering
- [ ] `AuthSecurityConfig` entry point and access denied handler updated to `ApiResponse`
- [ ] `application.yml` updated with expanded actuator endpoints
- [ ] `micrometer-registry-prometheus` dependency added to walmal-app pom.xml
- [ ] Integration test: unauthenticated request returns 401 in `ApiResponse` format
- [ ] Integration test: rate limit exceeded returns 429 in `ApiResponse` format
- [ ] Integration test: unknown endpoint returns 404 in `ApiResponse` format
- [ ] Integration test: `X-Correlation-Id` header present on all responses
- [ ] No new Maven modules created
- [ ] No new database tables
- [ ] No new RabbitMQ exchanges or events
