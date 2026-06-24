# Walmal Security Checklist — Manual Review Findings

**Date:** 2026-06-25
**Target:** `http://localhost:8080/api/v1`
**Backend:** Spring Boot 3 + Spring Security 6, stateless JWT (HS384), BCrypt cost 12
**Tester:** Automated (ZAP API scan + manual curl probes via sec-probes.sh)

---

## ZAP API Scan Summary

| Category      | Count |
|---------------|-------|
| URLs imported | 94    |
| URLs tested   | 249   |
| PASS          | 117   |
| WARN-NEW      | 3     |
| FAIL-NEW      | 0     |

### ZAP Warnings

| # | Alert | Severity | Count | Notes |
|---|-------|----------|-------|-------|
| 1 | Application Error Disclosure | MEDIUM | 5 | ZAP sent literal OpenAPI placeholder values (e.g. `productId`, `variantId`) as path params, causing `ResourceNotFoundException` → HTTP 500. Not a real injection; no stack trace returned. |
| 2 | Cross-Origin-Resource-Policy Header Missing | LOW | 4 | Public endpoints `/api/v1/product/categories`, `/api/v1/product/search`, `/api/v1/inventory/locations/default`, `/api-docs` |
| 3 | Server Error Response Code (500) | MEDIUM | 10 | Same placeholder-param issue as above. |

---

## Manual Security Checklist

### Authentication & Token Security

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 1 | Login response contains no sensitive data (password hash, secrets) | **PASS** | Response contains only `accessToken`, `refreshToken`, `userId`, `username`, `role`, `expiresIn` |
| 2 | JWT uses secure algorithm (not `none`, not RS256 downgrade) | **PASS** | JJWT 0.12.x, HS384; `Jwts.parser().verifyWith(secretKey)` rejects algorithm confusion and none attacks |
| 3 | JWT secret validated at startup (minimum length) | **PASS** | `JwtProperties` throws `IllegalStateException` if secret is blank or < 32 bytes |
| 4 | Invalid / expired JWT rejected (401) | **PASS** | `eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0IiwiZXhwIjoxfQ.invalid` → 401 |
| 5 | JWT `none` algorithm attack rejected (401) | **PASS** | Unsigned token with `"alg":"none"` and `"role":"ADMIN"` → 401 |
| 6 | Refresh token rotation enforced | **PASS** | First use of refresh token returns new pair; reusing original returns error |
| 7 | Passwords not logged | **PASS** | `RequestLoggingFilter` logs only `METHOD URI STATUS duration userId`; no body logging |

### Role-Based Access Control (RBAC)

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 8 | Customer cannot access admin user list (`GET /auth/users`) | **PASS** | → 403 |
| 9 | Customer cannot access warehouse inventory (`GET /inventory/stock`) | **PASS** | → 403 |
| 10 | Admin can access warehouse inventory | **PASS** | `GET /inventory/stock?page=0&size=1` with admin token → 200 |
| 11 | IDOR protection on orders | **PASS** | `OrderController.verifyOrderOwnership()` checks `principal.userId()` against order owner |
| 12 | Order list filtered by authenticated user | **PASS** | `GET /orders` uses `principal.userId()` — customers see only their own orders |

### Mass Assignment & Input Validation

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 13 | `role` field in registration request ignored | **PASS** | `RegisterRequest` accepts `role` field, but `AuthServiceImpl.register()` hardcodes `Role.CUSTOMER` regardless |
| 14 | XSS — script tag in search param not reflected | **PASS** | `GET /product/search?q=<script>alert(1)</script>` → JSON response, no reflection |
| 15 | SQL injection in search param handled safely | **PASS** | `GET /product/search?q='; DROP TABLE products; --` → 200 with empty results, no error |

### Transport & Headers

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 16 | CSRF protection appropriate for stateless JWT API | **PASS** | CSRF disabled by design; correct for stateless Bearer-token API. No session cookies used. |
| 17 | CORS rejects untrusted origins | **PASS** | `Origin: https://evil.com` → no `Access-Control-Allow-Origin` in response |
| 18 | `X-Frame-Options` / clickjacking protection | **PASS** | Spring Security default `X-Frame-Options: DENY` present |
| 19 | `X-Content-Type-Options` | **PASS** | `X-Content-Type-Options: nosniff` present |
| 20 | `Content-Security-Policy` header | **FAIL** | CSP header absent from API responses. Medium risk for any endpoints that serve HTML (Swagger UI). |
| 21 | `Cross-Origin-Resource-Policy` header | **FAIL** | Missing on public endpoints. Allows cross-origin embedding/loading of API responses. |
| 22 | `Server:` header does not leak version | **PASS** | No `Server:` header returned |

### Rate Limiting & Brute Force

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 23 | Rate limiting present for unauthenticated requests | **PASS** | Redis sliding-window rate limiter active; default 20 req/min per IP (overridden to 300 in dev/test) |
| 24 | Rate limiting present for authenticated requests | **PASS** | Per-username limit, default 100 req/min |
| 25 | Login timing is constant (no user enumeration via timing) | **FAIL** | Existing user (bcrypt check): ~0.262 s. Non-existent user (fast return): ~0.006 s. 44x difference enables user enumeration. |

### Information Disclosure

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 26 | Stack traces not returned in error responses | **PASS** | `GlobalExceptionHandler` catches all `Exception` and returns `"An unexpected error occurred."` |
| 27 | Actuator `/env` not exposed | **PASS** | → 401 (requires authentication) |
| 28 | Actuator `/health` detail exposure | **FAIL** (LOW) | `show-details: always` leaks DB type, disk path, Redis/RabbitMQ/MinIO liveness. Acceptable in dev; must change in prod. |
| 29 | Actuator `/metrics` not publicly accessible | **FAIL** (LOW) | `GET /actuator/metrics` → 200 without auth. Exposes JVM, thread pool, DB pool, HTTP request rate metrics. |
| 30 | Actuator `/prometheus` not publicly accessible | **FAIL** (LOW) | `GET /actuator/prometheus` → 200 without auth. Full Prometheus scrape endpoint, no auth. |
| 31 | Swagger UI / OpenAPI spec not publicly accessible | **INFO** | `/swagger-ui.html` and `/api-docs` are publicly accessible. Intentional for dev; should be disabled in prod. |
| 32 | MinIO credentials not hardcoded in production config | **PASS** | Dev defaults (`walmal`/`walmal123`) exist in `application.yml`; `application-prod.yml` requires env vars |

### CORS Configuration

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 33 | CORS allowed headers not wildcard (`*`) | **INFO** | `allowed-headers: ["*"]` — all request headers forwarded. Not a vulnerability, but prefer explicit allowlist in prod. |
| 34 | CORS allowed origins require explicit config in prod | **PASS** | `application-prod.yml` requires `WALMAL_CORS_ALLOWED_ORIGINS`; no default. Dev defaults to `localhost` family only. |

### Password Policy

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 35 | Password complexity requirements enforced | **INFO** | Minimum 8 characters only (`@Size(min=8)`). No requirements for uppercase, digit, or special character at the API level. The frontend and test data use stronger passwords, but the API does not enforce complexity. |

---

## Findings Summary

### HIGH — None

### MEDIUM

| ID | Finding | Location | Remediation |
|----|---------|----------|-------------|
| M1 | **User enumeration via login timing** | `POST /auth/login` | After `userRepository.findByUsername()` returns empty, run a dummy `passwordEncoder.matches()` call against a static placeholder hash (blind bcrypt). This equalizes timing for existing vs non-existent users. |
| M2 | **Missing Content-Security-Policy header** | All API + Swagger UI responses | Add `Content-Security-Policy: default-src 'none'` to API responses via `SecurityFilterChain.headers()`. For Swagger UI, configure an appropriate CSP allowing its CDN assets. |

### LOW

| ID | Finding | Location | Remediation |
|----|---------|----------|-------------|
| L1 | **Actuator `/metrics` and `/prometheus` unauthenticated** | `GET /actuator/metrics`, `GET /actuator/prometheus` | In `AuthSecurityConfig`, move these endpoints from `permitAll()` to require `ADMIN` role, or put behind a separate management port (`management.server.port`) not exposed publicly. |
| L2 | **Actuator `/health` leaks infrastructure detail** | `GET /actuator/health` | Set `management.endpoint.health.show-details: when-authorized` in `application.yml` (keep `always` only in dev profile). |
| L3 | **Missing `Cross-Origin-Resource-Policy` header** | Public GET endpoints | Add `Cross-Origin-Resource-Policy: same-site` (or `cross-origin` if CDN caching is desired) via Spring Security headers config. |
| L4 | **Swagger UI and OpenAPI spec publicly accessible in prod** | `/swagger-ui.html`, `/api-docs` | Disable via `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false` in `application-prod.yml`, or require auth. |

### INFO

| ID | Finding | Detail |
|----|---------|--------|
| I1 | CORS `allowed-headers: ["*"]` | Not exploitable, but prefer explicit header allowlist (`Authorization`, `Content-Type`, `X-Correlation-Id`) in production. |
| I2 | Password minimum only 8 characters | Consider adding `@Pattern` constraint on `RegisterRequest.password` to require at least one uppercase, one digit, and one special character. |
| I3 | ZAP 500 errors from placeholder params | Not real vulnerabilities — ZAP used literal OpenAPI template strings as param values. No stack trace or sensitive data returned. |

---

## Production Hardening Recommendations

1. **Blind bcrypt on missing user** — eliminates timing oracle for account enumeration (M1).
2. **Lock down actuator endpoints** — require `ADMIN` role or restrict to management port (L1, L2).
3. **Add Content-Security-Policy** — via `http.headers().contentSecurityPolicy(...)` in `SecurityFilterChain` (M2).
4. **Add Cross-Origin-Resource-Policy** — via `http.headers().crossOriginResourcePolicy(...)` (L3).
5. **Disable Swagger in prod** — set `springdoc.*.enabled=false` in `application-prod.yml` (L4).
6. **Use explicit CORS header allowlist** — replace `allowed-headers: ["*"]` with enumerated list (I1).
7. **JWT secret rotation plan** — document procedure for rotating `WALMAL_JWT_SECRET` (forces all sessions to invalidate); confirm refresh token blacklist in Redis survives rotation.
8. **TLS enforcement** — ensure prod deployment enforces HTTPS at load balancer/reverse proxy; add `Strict-Transport-Security` header.
9. **Dependency audit** — run `./mvnw dependency-check:check` (OWASP) quarterly to catch CVEs in transitive dependencies.
