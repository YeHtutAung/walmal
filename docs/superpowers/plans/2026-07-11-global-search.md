# Global Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A top-bar search box in the admin panel that, on Enter, lands on a `/search?q=` results page with three independently-gated sections (Orders / Products / Users), backed by one widened and two new per-module backend search endpoints.

**Architecture:** Frontend fan-out — the results page makes three parallel requests to per-module search endpoints; no backend aggregator (a three-way aggregation hub has no natural owner under ADR-4's per-module data ownership). Search-on-Enter only (production rate limit is 60 authenticated req/min; type-ahead fan-out would 429 mid-word). Product search widens its matching (name/brand → +SKU +barcode) but keeps its exact current length semantics — its empty-`q` behavior is the admin products list's list-all path and MUST NOT change. The two new endpoints (orders, users) get a short-`q` guard: trimmed `q` under 2 chars returns an empty page without touching the database.

**Tech Stack:** Backend — Spring Boot, Spring Data JPA (one JPQL with explicit countQuery, one JPQL with a UUID→string CAST, one derived query), JUnit 5 + Mockito + Testcontainers. Frontend — React, Refine `useCan` gating, shared `useAsyncData`/`unwrap` from `src/lib/`, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-07-11-global-search-design.md` (same repo) — read for full rationale, including three corrections made during review (the walmal-notification→auth dependency precedent, barcode's existence and user-approved inclusion, the product-search guard exemption).

---

## Repos and worktrees

Spans two sibling repos. **Tasks 1–5 execute against `walmal`; Tasks 6–9 execute against `walmal-admin`.** Two separate worktrees via `using-git-worktrees`. Finish and merge the `walmal` side first (Task 9's e2e needs live endpoints; rebuild the backend JAR after merging — the stale-JAR gotcha applies to every backend change).

---

## File Structure

**Modified (`walmal/walmal-product/`):**
- `src/main/java/com/walmal/product/infrastructure/ProductRepository.java` — new JPQL search query
- `src/main/java/com/walmal/product/application/impl/ProductSearchServiceImpl.java` — blank-`q` branch + pattern building
- `src/main/java/com/walmal/product/api/ProductController.java` — `@Operation` description only

**New (`walmal/walmal-product/`):**
- `src/test/java/com/walmal/product/application/ProductSearchServiceImplTest.java` (no unit test exists for this service today)
- `src/test/java/com/walmal/product/infrastructure/ProductSearchIntegrationTest.java`

**Modified (`walmal/walmal-order/`):**
- `src/main/java/com/walmal/order/infrastructure/OrderRepository.java`
- `src/main/java/com/walmal/order/application/OrderAdminService.java` + `impl/OrderAdminServiceImpl.java`
- `src/main/java/com/walmal/order/api/OrderController.java`
- `src/test/java/com/walmal/order/api/OrderControllerTest.java`
- `src/test/java/com/walmal/order/application/impl/OrderAdminServiceDailySummaryTest.java` — NO; new file below

**New (`walmal/walmal-order/`):**
- `src/test/java/com/walmal/order/application/impl/OrderAdminServiceSearchTest.java`
- `src/test/java/com/walmal/order/OrderSearchIntegrationTest.java`

**Modified (`walmal/walmal-auth/`):**
- `src/main/java/com/walmal/auth/infrastructure/UserRepository.java`
- `src/main/java/com/walmal/auth/application/AuthService.java` + its impl
- `src/main/java/com/walmal/auth/api/AuthController.java`
- `src/test/java/com/walmal/auth/api/AuthControllerTest.java`
- `src/test/java/com/walmal/auth/application/AuthServiceImplTest.java`

**KB (`walmal`):** `docs/kb/SYSTEM.md`, `docs/kb/architecture.md`

**New (`walmal-admin/src/`):**
- `pages/search/search-helpers.ts` + `search-helpers.test.ts`
- `pages/search/index.tsx` (page: URL param + gate on query validity + `key={q}` remount wrapper)
- `pages/search/SearchResults.tsx` (the three sections; may be split further per implementer judgment)

**Modified (`walmal-admin/`):**
- `src/components/layout/Header.tsx` — search box + `getPageTitle` entry
- `src/App.tsx` — `/search` route
- `src/lib/axios-client.ts` — export shared `API_BASE` (third duplication of the `BASE` const is the agreed trigger to extract; update the two existing copies in `dashboard/index.tsx` and `categories/list.tsx` to import it)
- `tests/e2e/dashboard.spec.ts` or a new `tests/e2e/search.spec.ts` (implementer judgment; likely new file since it's a new page)
- `docs/kb/architecture.md`, `docs/kb/testing.md`

---

## Backend tasks (`walmal`)

### Task 1: Product search — widen to SKU + barcode (TDD)

**Files:** `ProductRepository.java`, `ProductSearchServiceImpl.java`, `ProductController.java` (docs only), new `ProductSearchServiceImplTest.java`

**Critical constraint:** empty/blank `q` is the admin products list's list-all path. Its behavior must be byte-for-byte preserved. Only non-blank `q` takes the new widened query.

- [ ] **Step 1: Write the failing unit test** — new file (none exists; follow `ProductCatalogServiceImplTest`'s exact convention: `@ExtendWith(MockitoExtension.class)`, `@Mock` repos, constructor wiring in `@BeforeEach`, `should_X_when_Y` + `@DisplayName` naming). Cases:
  - blank `q` (`""` and `"   "`) → calls `productRepository.findAll(pageable)`, `verify(productRepository, never()).searchByNameBrandSkuOrBarcode(any(), any())`
  - non-blank `q` `"AbC"` → calls `searchByNameBrandSkuOrBarcode(eq("%abc%"), any(Pageable.class))` (trimmed, lowercased, wrapped) and never `findAll`
  - Note: `ProductSearchServiceImpl`'s constructor takes 6 deps (`productRepository, categoryRepository, priceRepository, imageRepository, imageStorageAdapter, cacheService`) — mock all 6.

- [ ] **Step 2: Run to verify it fails** — `./mvnw -pl walmal-product -am test -Dtest=ProductSearchServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Add the repository query** (`ProductRepository.java` — needs new `@Query`/`@Param` imports, none present today):

```java
@Query(value = "SELECT DISTINCT p FROM Product p LEFT JOIN p.variants v " +
               "WHERE lower(p.name) LIKE :q OR lower(p.brand) LIKE :q " +
               "OR lower(v.sku) LIKE :q OR lower(v.barcode) LIKE :q",
       countQuery = "SELECT COUNT(DISTINCT p) FROM Product p LEFT JOIN p.variants v " +
               "WHERE lower(p.name) LIKE :q OR lower(p.brand) LIKE :q " +
               "OR lower(v.sku) LIKE :q OR lower(v.barcode) LIKE :q")
Page<Product> searchByNameBrandSkuOrBarcode(@Param("q") String qContainsLowercase, Pageable pageable);
```

The **explicit `countQuery` is mandatory** — Spring Data's derived count for a `SELECT DISTINCT` + join can over-count joined rows, corrupting `totalElements`/pagination. The integration test (Task 4) proves the count. Leave the old derived `findByNameContainingIgnoreCaseOrBrandContainingIgnoreCase` in place only if something else uses it — grep first; if `searchProducts` was its only caller, delete it.

- [ ] **Step 4: Update `ProductSearchServiceImpl.searchProducts`:**

```java
@Override
public Page<ProductSummaryDto> searchProducts(String query, Pageable pageable) {
    if (query == null || query.isBlank()) {
        // list-all path — the admin products list depends on this exact behavior
        return productRepository.findAll(pageable).map(this::toProductSummaryDto);
    }
    String pattern = "%" + query.trim().toLowerCase() + "%";
    return productRepository.searchByNameBrandSkuOrBarcode(pattern, pageable).map(this::toProductSummaryDto);
}
```

(Do NOT touch the known N+1 in `toProductSummaryDto` — pre-existing, out of scope.) Update `ProductController.searchProducts`'s `@Operation` description to "name, brand, SKU, or barcode".

- [ ] **Step 5: Run to verify pass + full module suite** — unit test passes; `./mvnw -pl walmal-product -am test` green (28+ tests; `ProductControllerTest`'s existing search test mocks the service, unaffected).

- [ ] **Step 6: Commit** — `feat(product): widen product search to variant SKU and barcode`

---

### Task 2: Orders search endpoint (TDD)

**Files:** `OrderRepository.java`, `OrderAdminService.java`, `OrderAdminServiceImpl.java`, `OrderController.java`, new `OrderAdminServiceSearchTest.java`, `OrderControllerTest.java`

- [ ] **Step 1: Failing service unit test** — new `OrderAdminServiceSearchTest.java` (mirror `OrderAdminServiceDailySummaryTest`'s exact setup: `@ExtendWith(MockitoExtension.class)`, `@Mock OrderRepository/DomainEventPublisher/AuditService`, `@BeforeEach` wiring, `should_X_when_Y` naming). Cases:
  - `q = "a"` (1 char) and `q = " "` → returns empty `Page`, `verify(orderRepository, never())` on the search query
  - `q = "AbC12"` → repository called with `qPrefix = "abc12%"` and `qContains = "%abc12%"`; result rows mapped to `OrderAdminSummaryDto` with the same field order `listAllOrders` uses `(id, userId, status, totalAmount, currency, itemCount, createdAt)`

- [ ] **Step 2: Verify red**, then **Step 3: implement:**

`OrderRepository.java`:
```java
@Query("SELECT o FROM Order o " +
       "WHERE lower(CAST(o.id AS string)) LIKE :qPrefix " +
       "OR lower(o.guestEmail) LIKE :qContains")
Page<Order> searchByIdPrefixOrGuestEmail(@Param("qPrefix") String qPrefixLowercase,
                                         @Param("qContains") String qContainsLowercase,
                                         Pageable pageable);
```

(`lower(CAST(...))` because Postgres renders UUIDs lowercase but an admin may paste uppercase. `guestEmail` is null for registered-customer orders — `lower(null) LIKE` is simply non-matching, no special handling; integration test proves it.)

`OrderAdminService` + impl:
```java
@Override
public Page<OrderAdminSummaryDto> searchOrders(String q, Pageable pageable) {
    if (q == null || q.trim().length() < 2) {
        return Page.empty(pageable);
    }
    String needle = q.trim().toLowerCase();
    return orderRepository.searchByIdPrefixOrGuestEmail(needle + "%", "%" + needle + "%", pageable)
            .map(this::toAdminSummary);
}
```

Extract the existing mapping lambda in `listAllOrders` into a private `toAdminSummary(Order)` helper and use it from both methods (don't duplicate the 7-field constructor call).

`OrderController.java` — after the existing `/admin/daily-summary` endpoint, same style:
```java
@GetMapping("/admin/search")
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
@Operation(summary = "Search orders (admin)",
           description = "Matches order-ID prefix or guest-email substring, case-insensitive. q under 2 chars returns an empty page. Admin and Staff only.")
public ApiResponse<Page<OrderAdminSummaryDto>> searchOrders(
        @RequestParam String q,
        @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
    return ApiResponse.ok(orderAdminService.searchOrders(q, pageable));
}
```

- [ ] **Step 4: WebMvcTest additions** (`OrderControllerTest.java`, existing `buildAuth` pattern): ADMIN → 200 + `$.success=true` + `$.data.content[0]` field check (stub the service); CUSTOMER → 403. (The short-`q` guard lives in the service and is unit-tested there — the controller test mocks the service, so don't fake a guard test at this layer.)

- [ ] **Step 5: Full module suite green** (`./mvnw -pl walmal-order -am test`), **Step 6: Commit** — `feat(order): add admin order search by ID prefix or guest email`

---

### Task 3: Users search endpoint (TDD)

**Files:** `UserRepository.java`, `AuthService.java` + impl, `AuthController.java`, `AuthServiceImplTest.java`, `AuthControllerTest.java`, **plus (opening move, per Task 2's code review): new `walmal-common` `LikePatterns` utility** and retrofits in `ProductSearchServiceImpl`/`OrderAdminServiceImpl`

**Step 0 (added after Task 2's review — rule of three):** `escapeLikeWildcards` now exists in two textually-divergent copies (inlined in `ProductSearchServiceImpl`, named helper in `OrderAdminServiceImpl`); Task 3 would create a third... except the users search uses a DERIVED `ContainingIgnoreCase` query which auto-escapes, so it does NOT need the helper. Therefore Step 0 is: extract `com.walmal.common.util.LikePatterns.escape(String)` (javadoc: backslash first, then `%`, `_`; used with `ESCAPE '\'` predicates) into `walmal-common`, retrofit the two existing call sites to use it, run both modules' suites green, and commit separately (`refactor: extract LIKE wildcard escaping to walmal-common`). The existing per-module unit tests are the regression net — they must pass unchanged.

- [ ] **Step 1: Failing service unit test** — add to the existing `AuthServiceImplTest.java` (23 tests today; follow its in-file conventions exactly). Cases: short `q` → empty page, repository never called; valid `q "AbC"` → `findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase("AbC", "AbC", pageable)` called (derived query handles case-insensitivity itself — pass the trimmed raw `q`, do NOT pre-lowercase) and rows mapped via the existing `toProfile` (assert `UserProfileResponse` fields).

- [ ] **Step 2: Verify red**, then **Step 3: implement:**

`UserRepository.java`:
```java
Page<User> findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(
        String username, String email, Pageable pageable);
```

`AuthService` + `AuthServiceImpl` (near `listUsers`, reusing `toProfile`):
```java
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
```

`AuthController.java` — after `listUsers`, matching its **bare-`Page`** response shape (no `ApiResponse` envelope — the auth module's own convention):
```java
@GetMapping("/users/search")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Page<UserProfileResponse>> searchUsers(
        @RequestParam String q, Pageable pageable) {
    return ResponseEntity.ok(authService.searchUsers(q, pageable));
}
```

(Route-ordering check: confirm `/users/search` isn't shadowed by any `/users/{id}` path-variable mapping in this controller — read the actual mappings; Spring prioritizes exact segments over variables, but verify there IS a `/users/{id}` GET and note the test proves resolution.)

- [ ] **Step 4: WebMvcTest additions** (`AuthControllerTest.java`, existing `buildAuth`): ADMIN → 200 + `$.content[0].username` (bare-Page JSON path, NOT `$.data...`); STAFF → 403.

- [ ] **Step 5: Full module suite green** (`./mvnw -pl walmal-auth -am test` — 49 tests today), **Step 6: Commit** — `feat(auth): add admin user search by username or email`

---

### Task 4: Integration tests (product + order)

**Files:** new `ProductSearchIntegrationTest.java` (walmal-product), new `OrderSearchIntegrationTest.java` (walmal-order)

- [ ] **Step 1: Product** — follow `ProductIntegrationTest`'s exact setup (`@Tag("integration")`, Testcontainers postgres:15-alpine, `TestInfrastructureConfig` no-op beans, seeding via `ProductManagementService.createCategory/createProduct/createVariant` — `CreateVariantRequest` takes `(sku, variantName, barcode, …, price, currency)`, barcode nullable). Autowire `ProductSearchService`. Seed: product A (name "Alpha Widget", brand "Acme") with variant sku `ZED-001` barcode `9990001`, and a second variant sku `ZED-002` barcode null; product B (name "Beta Gadget") with variant sku `QQQ-100` barcode `8880002`. Assert:
  - `q="zed"` → exactly 1 row (product A — **`DISTINCT` dedupes the two matching variants**), `totalElements == 1` (**proves the countQuery**)
  - `q="9990001"` → product A (barcode-only match); `q="8880002"` → product B
  - variant with null barcode broke nothing (covered implicitly by the above; assert no exception + correct results)
  - `q="alpha"` → product A (name regression); `q="acme"` → product A (brand regression)
  - `q=""` → both products (list-all preserved)
  - **Literal-wildcard case (added after Task 1's review):** seed a variant with sku containing a literal underscore (e.g. `AB_C1`); assert `q="ab_c1"` matches it AND `q="ab-c1"` (or any single-char substitution like `abXc1`) does NOT — proving the `ESCAPE` clause works end-to-end against real Postgres, not just in the unit-level pattern assertion.

- [ ] **Step 2: Order** — follow `OrderDailySummaryIntegrationTest`'s exact setup (JdbcTemplate INSERT with explicit known UUIDs — required here since ID-prefix matching needs controlled IDs; `@BeforeEach DELETE FROM order_orders` to clear the Flyway dev-seed row). Seed: order 1 id `aaaa1111-…` guest email `search-me@example.com`; order 2 id `bbbb2222-…` userId set, guestEmail NULL. Call `orderAdminService.searchOrders(...)`. Assert:
  - `q="aaaa1111"` → order 1 only; `q="AAAA1111"` (uppercase) → order 1 (case-folding on the CAST proven against real Postgres)
  - `q="search-me"` → order 1; `q="bbbb"` → order 2 (**null guestEmail row matched by ID and didn't blow up the query**)
  - `q="zzzz"` → empty page

- [ ] **Step 3: Run both** — `./mvnw -pl walmal-product -am test -DexcludedGroups= -Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ProductSearchIntegrationTest` and the walmal-order equivalent. Docker required (`docker ps` first).

- [ ] **Step 4: Commit** — `test: add integration coverage for product and order search queries`

---

### Task 5: Backend KB updates

**Files:** `docs/kb/SYSTEM.md`, `docs/kb/architecture.md`

- [ ] `SYSTEM.md` — the Admin-Facing Endpoint Contracts table gains/updates three rows: product search (semantics **changed**: now matches SKU + barcode; empty-`q` list-all behavior explicitly documented as load-bearing), orders search (new), users search (new — note the bare-`Page` response shape, no envelope).
- [ ] `architecture.md` — the three endpoints; the short-`q` guard convention **and its product-search exemption with the reason** (empty `q` = products list's list-all path); the fan-out-over-aggregation decision. **Do NOT write "no module depends on walmal-auth" — that claim is false** (`walmal-notification` consumes `StaffNotificationQueryService` at compile scope); the correct rationale is "a three-way aggregation hub has no natural owner."
- [ ] **Commit** — `docs(kb): document global search endpoints and fan-out rationale`

---

## Frontend tasks (`walmal-admin`)

**Before starting:** backend Tasks 1–5 merged to `walmal` main; rebuild the JAR (`./mvnw -pl walmal-app -am -DskipTests clean package`).

### Task 6: Search helpers pure logic (TDD)

**Files:** new `src/pages/search/search-helpers.ts` + `search-helpers.test.ts`

- [ ] Failing test first: `isSearchableQuery` — `""` false, `"a"` false, `"ab"` true, `"  a  "` false, `" ab "` true, `"a b"` true (3 chars incl. space). Then implement:

```typescript
// Pure query validation for global search — shared by the header search box
// and the /search results page so both enforce the same 2-character minimum.
// No React, no fetching — independently unit-testable (see search-helpers.test.ts).

export const MIN_QUERY_LENGTH = 2;

export function isSearchableQuery(q: string): boolean {
  return q.trim().length >= MIN_QUERY_LENGTH;
}
```

- [ ] `npm run test:unit -- search-helpers` green. **Commit** — `feat: add global search query validation helper`

---

### Task 7: Header search box

**Files:** `src/components/layout/Header.tsx`

- [ ] Add a search `<Input>` (import from `@/components/ui/input`) between the `<h1>` title and the `ml-auto` cluster: placeholder **exactly** `"Search orders, SKUs, users…"` (Task 9's e2e selector will use it — pinned now), responsive-hidden on small screens (match the header's existing `hidden sm:block` treatment or one step wider — implementer's visual judgment), reasonable width (`w-64`-ish). Local `useState` for the value; on Enter (`onKeyDown`), if `isSearchableQuery(value)` → `navigate(\`/search?q=${encodeURIComponent(value.trim())}\`)` (add `useNavigate` import). No fetch from the header, ever.
- [ ] `getPageTitle` (same file, L12–39): add `if (pathname === "/search") return "Search";`
- [ ] `npx tsc -b --noEmit && npm run lint` clean. **Commit** — `feat: add global search box to admin header`

---

### Task 8: `/search` results page

**Files:** new `src/pages/search/index.tsx` + `SearchResults.tsx`; modify `src/App.tsx`, `src/lib/axios-client.ts`, `src/pages/dashboard/index.tsx`, `src/pages/categories/list.tsx`, `docs/kb/architecture.md`

- [ ] **Step 1: Extract `API_BASE`** — this page would add a SEVENTH copy of `const BASE = \`${import.meta.env.VITE_API_BASE_URL}/api/v1\`` (plan review found six existing: `dashboard/index.tsx`, `categories/list.tsx`, `providers/walmal-data-provider.ts`, `pages/orders/show.tsx`, `pages/orders/edit.tsx`, `pages/pos/terminals/show.tsx`). Add to `src/lib/axios-client.ts`: `export const API_BASE = \`${import.meta.env.VITE_API_BASE_URL}/api/v1\`;` and update **all six** existing local copies to import it (each is a one-line mechanical change; verify each file's const is genuinely identical before replacing — if any variant differs, leave it and note why). Run `npm run test:unit` after this step alone — pure mechanical refactor, must stay green.

- [ ] **Step 2: Page shell** (`src/pages/search/index.tsx`):

```tsx
export default function SearchPage() {
  const [searchParams] = useSearchParams();
  const q = searchParams.get("q") ?? "";
  if (!isSearchableQuery(q)) {
    return <div className="text-sm text-muted-foreground">Type at least 2 characters to search.</div>;
  }
  return <SearchResults key={q} q={q.trim()} />;
}
```

**The `key={q}` remount is load-bearing, not cosmetic:** `useAsyncData` re-runs only on `enabled` flip or `refetch()` — NOT when the fetcher closure changes. Navigating `/search?q=a` → `/search?q=b` without the key would render stale results forever. Remounting `SearchResults` on `q` change resets all three fetches cleanly. Put this exact explanation in a code comment.

- [ ] **Step 3: `SearchResults.tsx`** — three sections, each: `useCan` gate (`orders:list` / `products:list` / `users:list`), `useAsyncData` fetch (enabled = its gate), `Skeleton`/`WidgetError`/empty-state per the established widget pattern. Fetches (all `page=0&size=10`, `encodeURIComponent(q)`):
  - Orders: `GET ${API_BASE}/orders/admin/search?q=…` → `unwrap<{content: OrderRow[]; totalElements: number}>` — row: short ID (`id.substring(0,8).toUpperCase()`), status badge, `totalAmount`+`currency`, `createdAt` date → click `navigate(\`/orders/${id}\`)`
  - Products: `GET ${API_BASE}/product/search?q=…` — row: `primaryImageUrl` thumb (muted placeholder when null), name, brand, `lowestPrice`+`currency` → click `/products/{id}`
  - Users: `GET ${API_BASE}/auth/users/search?q=…` (bare `Page` — `unwrap` passes it through) — row: username, email, role badge → click **`/users/{id}/edit`** (the `/users/:id` route is a dead stub — spec-documented)
  - Each section header shows the count; when `totalElements > 10`, a muted "showing 10 of N" note.
  - Steal row/badge styling from the dashboard's Recent Orders table and the users list page — no new visual language.

- [ ] **Step 4: Route** — `App.tsx`, right after the dashboard `index` route (L153): `<Route path="/search" element={<SearchPage />} />` (inside the authenticated layout; no `resources[]` entry needed — first non-resource route, precedent-setting and fine).

- [ ] **Step 5: Verify** — `npx tsc -b --noEmit && npm run lint && npm run test:unit` clean/green. **Live check** (backend JAR running): search a seeded product name, a seeded SKU (e.g. from V12 test data), an order-ID prefix (grab one from the orders list), and a username (`admin`) — confirm all three sections behave, gating hides Users for a STAFF login if convenient to check, and `/search?q=a` shows the hint without fetching (check the network tab).

- [ ] **Step 6: KB** — `walmal-admin/docs/kb/architecture.md`: header search, `/search` page, the `key={q}` remount trap (gotcha-worthy — put it where the `useAsyncData` lib is documented), `API_BASE` extraction. **Commit** — `feat: add global search page with orders, products, and users sections`

---

### Task 9: E2E smoke + final KB

**Files:** new `tests/e2e/search.spec.ts`, `docs/kb/testing.md`

- [ ] Two tests in a new `test.describe("Global search")`:

```typescript
test("header search navigates to results page", async ({ page }) => {
  await page.goto("/");
  const box = page.getByPlaceholder("Search orders, SKUs, users…");
  await box.fill("galaxy");
  await box.press("Enter");
  await expect(page).toHaveURL(/\/search\?q=galaxy/);
  await expect(page.getByText("Orders")).toBeVisible();
  await expect(page.getByText("Products")).toBeVisible();
  await expect(page.getByText("Users")).toBeVisible();
});

test("product search finds seeded product", async ({ page }) => {
  await page.goto("/search?q=galaxy");
  await expect(page.getByText(/Galaxy S24/i).first()).toBeVisible();
});
```

(Adjust the seeded-name assumption against reality: "Galaxy S24 Ultra" is seeded by `V9__seed_dev_data.sql` — V12 seeds test users/variants — confirm the parent product's exact name via the products list before hardcoding; also tighten the section-heading selectors to whatever Task 8 actually rendered, e.g. `getByRole("heading", …)`, to avoid matching stray text.)

- [ ] Run the full suite (`npm run test:e2e`; backend JAR running per the documented worktree gotcha) — expect 17 existing + 2 new = 19. Update `docs/kb/testing.md` count + breakdown. **Commit** — `test: add e2e smoke coverage for global search`

---

## Final verification checklist

- [ ] `walmal`: `./mvnw test` (full reactor) — all modules green
- [ ] `walmal`: both new integration tests pass (Docker required)
- [ ] `walmal-admin`: `npm run test:unit` — all green (39 existing + new helper tests)
- [ ] `walmal-admin`: `npx tsc -b --noEmit`, `npm run build`, `npm run lint` (15-problem baseline unchanged)
- [ ] `walmal-admin`: `npm run test:e2e` — 19/19 against a rebuilt JAR
- [ ] The admin products list page still lists all products (the empty-`q` regression check, live)
- [ ] Backend KB updated by Task 5 (deliberately batched for the three related endpoints — one contract story, one commit) and frontend KB in Tasks 8-9's own commits
