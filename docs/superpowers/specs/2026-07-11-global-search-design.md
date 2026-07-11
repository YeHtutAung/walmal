# Global Search — Design

**Status:** Approved (section-by-section, 2026-07-11)
**Repos affected:** `walmal` (backend — one changed endpoint in `walmal-product`, one new endpoint each in `walmal-order` and `walmal-auth`), `walmal-admin` (frontend — top-bar search box + new `/search` results page)

## Context

The original Ops Dashboard mockup had a top-bar search box with placeholder "Search orders, SKUs,
users…". Phase 1 deferred it entirely ("nothing like it exists anywhere in the stack"), and that
remained true at design time:

- **Products**: `GET /api/v1/product/search?q=` exists but matches name + brand only. No SKU or
  barcode search *surface* exists anywhere (the `sku` and `barcode` columns exist on
  `ProductVariant`, and the cross-module `ProductCatalogService.findVariantBySku` exists, but
  nothing is wired to HTTP).
- **Orders**: `GET /api/v1/orders/admin` filters by status only. No text/ID/email search exists.
- **Users**: `GET /api/v1/auth/users` filters by role/active only. No username/email search exists.

## Architecture decision: frontend fan-out, not a backend aggregation endpoint

The results page makes **three parallel requests** to per-module search endpoints and renders three
independent sections. A single backend `GET /search` aggregator was considered and rejected:

- An aggregator has no natural owner: it would need product + order + auth data simultaneously,
  and whichever module hosts it must take compile dependencies on the other two — a "god module"
  shape ADR-4's per-module data ownership is designed to avoid. (Precedent nuance, corrected
  during spec review: a compile-scope business dependency on `walmal-auth` is *not* unprecedented —
  `walmal-notification` already consumes auth's `StaffNotificationQueryService`. But one existing
  targeted edge doesn't justify a three-way aggregation hub; auth's only cross-module identity
  interface, `UserLookupService`, is lookup-by-ID, not search, and is referenced by nothing
  outside auth today.)
- Per-module search endpoints are independently useful (each module's own list pages can adopt them
  later), and each module keeps owning its own data access per ADR-4.

Do not "unify" this into a cross-module aggregator later without confronting the dependency-edge
problem above — this decision should be documented in the backend KB.

## Interaction model: search on Enter, results page (not type-ahead)

Explicitly chosen over a live type-ahead dropdown:

- Production rate limit is **60 authenticated requests/min per user** (fixed window). A
  3-request-per-keystroke fan-out would 429 an admin after ~20 keystrokes. Search-on-Enter costs 3
  requests per explicit search — no exposure.
- No command-palette UI primitives (`cmdk`, `Command`, `popover`, `dialog`) exist in the frontend;
  a results page reuses existing card/table/skeleton patterns with zero new primitives.
- No debounce precedent exists anywhere in the frontend (existing search inputs fetch per
  keystroke — a known, tolerated MVP behavior on those pages, not a pattern to amplify by 3x).

## Backend design (`walmal`)

Common rule for all three: **a `q` shorter than 2 characters (after trim) returns an empty page
without querying the database** — guards against full-table ILIKE scans on 1-character queries.

### 1. Product — extend existing search to SKUs (`walmal-product`)

`GET /api/v1/product/search?q=` — same endpoint, same `ApiResponse<Page<ProductSummaryDto>>`, same
pagination; only the matching semantics widen. Replace the current derived query
(`findByNameContainingIgnoreCaseOrBrandContainingIgnoreCase`) with a JPQL query:

```
SELECT DISTINCT p FROM Product p LEFT JOIN p.variants v
WHERE lower(p.name) LIKE :qContains
   OR lower(p.brand) LIKE :qContains
   OR lower(v.sku) LIKE :qContains
```

- A SKU hit returns the **parent product** (the search results page shows products, not variants).
- `DISTINCT` prevents duplicate rows when multiple variants of one product match.
- LEFT JOIN (not inner) so products with zero variants still match on name/brand.
- Pagination on a `SELECT DISTINCT` + join: verify Spring Data generates a correct count query
  (may need an explicit `countQuery` on the `@Query` annotation — decide at implementation time,
  prove with the integration test).

### 2. Orders — new dedicated search endpoint (`walmal-order`)

`GET /api/v1/orders/admin/search?q=` — `@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")` (same gate
as `/orders/admin`), `Pageable`, returns `ApiResponse<Page<OrderAdminSummaryDto>>` (the existing
DTO). Matches:

- **Order-ID prefix**: `CAST(o.id AS string) LIKE :qPrefix` (lowercased both sides) — prefix, not
  contains, because the real workflow is an admin pasting the start of an ID from a customer
  email/screenshot. UUID-to-string casting in JPQL against real Postgres is exactly the kind of
  thing that compiles but can surprise — the integration test must prove it.
- **Guest email contains**: `lower(o.guestEmail) LIKE :qContains` (null-safe — registered-customer
  orders have null `guestEmail`).

Kept as a separate endpoint (not a `q` param on `/orders/admin`) so the existing status-filter
endpoint's semantics stay untouched.

### 3. Users — new dedicated search endpoint (`walmal-auth`)

`GET /api/v1/auth/users/search?q=` — `@PreAuthorize("hasRole('ADMIN')")` (same gate as the existing
users list), `Pageable`, returns `Page<UserProfileResponse>` — the same DTO **and the same
bare-`Page` response shape** as the existing `GET /auth/users` list (the auth module returns pages
without the `ApiResponse` envelope, unlike product/order — match the module's own convention; the
frontend's shared `unwrap` already tolerates both shapes). Matches username OR email, contains,
case-insensitive — a standard derived query
(`findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase`).

### No new database migration — all three query existing tables/columns.

## Frontend design (`walmal-admin`)

### Top-bar search box

`Header.tsx` gains an `<Input>` (placeholder "Search orders, SKUs, users…") between the page title
and the user email/badge cluster, hidden on small screens (the header's existing email uses
`hidden sm:block` — pick a matching or slightly wider breakpoint for the search box at
implementation time; exact class is an implementation detail, not a contract). **Enter navigates to `/search?q=<encoded>`** — the box
never fetches on keystroke, so it has zero rate-limit exposure. `getPageTitle()` gains a `/search`
→ "Search" entry.

### `/search` results page

New route in `App.tsx` (inside the authenticated layout) → `src/pages/search/index.tsx`:

- Reads `q` from the URL (shareable/refreshable results).
- A `q` under 2 characters (after trim) renders a "type at least 2 characters" hint and fetches
  nothing — mirroring the backend guard.
- Fires three parallel fetches via the shared `useAsyncData` + `unwrap` from `src/lib/` (extracted
  in the previous feature — first reuse).
- Three sections, each independently gated, fetched, and error-handled (a users-fetch failure must
  not blank the orders results):

| Section | Gate (`useCan`) | Endpoint | Row shows | Row click target |
|---|---|---|---|---|
| Orders | `orders:list` | `/orders/admin/search?q=` | short ID, status badge, total, date | `/orders/{id}` |
| Products | `products:list` | `/product/search?q=` | thumbnail, name, brand, price | `/products/{id}` |
| Users | `users:list` (ADMIN in practice) | `/auth/users/search?q=` | username, email, role badge | `/users/{id}/edit` |

**User row click targets `/users/{id}/edit`, not `/users/{id}`** — discovered during spec review:
the `/users/:id` route exists in `App.tsx` but renders a hardcoded placeholder stub (`<div>User
Detail</div>`); the edit page is the only working user detail surface. Upgrading the stub is out
of scope here.

- Each section fetches only the first page (`size=10`) and shows "showing 10 of N" when
  `totalElements` exceeds it — this is a cross-domain overview, not a browse UI; deep pagination
  belongs on the existing per-domain list pages.
- Loading/error/empty states per section via the established `Skeleton`/`WidgetError` pattern.

### Pure logic

`src/pages/search/search-helpers.ts` + test, per this repo's established colocated-pure-function
convention: `isSearchableQuery(q)` (trim + min-length validation, shared by the header box and the
results page) and any row-shaping that has real logic. Section components stay thin renderers.

## Testing plan

### Backend (`walmal`)

- **Unit tests** (per module): the short-`q` guard — sub-2-char returns an empty page without
  touching the repository (verify via mock: repository never called).
- **`@WebMvcTest`** (per new/changed endpoint): auth boundaries (users search → 403 for STAFF;
  orders search → 403 for CUSTOMER), response shape, short-`q` behavior at the HTTP layer.
- **Integration tests** (Testcontainers, real Postgres) for the two genuinely novel queries:
  - `walmal-product`: SKU-only match returns the parent product; `DISTINCT` deduplicates
    multi-variant matches (seed one product with 2+ matching SKUs, assert one row); name/brand
    matching still works (no regression); pagination count is correct with the join.
  - `walmal-order`: ID-prefix matching works against real UUIDs; guest-email contains-matching
    works; registered-customer orders (null guestEmail) don't blow up the query.
  - `walmal-auth`: standard derived query, low novelty — unit + WebMvcTest suffice; no integration
    test unless the implementer hits surprises (the module's integration infra works, fixed two
    features ago).

### Frontend (`walmal-admin`)

- **Unit tests**: `search-helpers.ts` query-validation boundaries (empty, 1-char, 2-char,
  whitespace-padded).
- **E2E smoke tests**: header search + Enter lands on `/search?q=...` with the three section
  headings visible (as ADMIN); searching a seeded product name renders at least one product row.
  No exact-count assertions (seeded-data fragility, per established convention).

## KB updates (both repos, same commits — cross-repo contract changes)

- `walmal/docs/kb/SYSTEM.md`: three contract entries — one **changed** (product search now also
  matches SKU), two **new** (orders search, users search).
- `walmal/docs/kb/architecture.md`: the endpoints, the short-`q` guard convention, and the
  deliberate fan-out-over-aggregation decision with its reason (a three-way aggregation hub has no
  natural owner — see the architecture-decision section; do NOT write "no module depends on auth"
  into the KB, that claim is false: `walmal-notification` already does).
- `walmal-admin/docs/kb/architecture.md`: header search box, `/search` route/page, section gating.
- `walmal-admin/docs/kb/testing.md`: bumped e2e count.

## Explicitly out of scope

- Live type-ahead / command-palette UI (rate-limit and primitive-availability reasons above; the
  results-page contract doesn't preclude adding it later).
- Barcode search — deferred, not impossible: `ProductVariant.barcode` *does* exist (nullable
  column; an earlier draft of this spec wrongly claimed otherwise — corrected during spec review),
  and adding it later is one more OR clause in the same JPQL plus a test case. Deferred because
  the approved scope was the mockup's literal "SKUs" promise; flag to the product owner as a cheap
  follow-up toggle.
- Order search by amount/date ranges (deferred; ID-prefix + guest email covers the actual admin
  workflow).
- Searching registered customers' emails on orders (order rows store `userId`, not email — joining
  user emails onto orders is exactly the cross-module problem this design avoids; an admin can
  search the user by email in the Users section and navigate from there).
- Relevance ranking / fuzzy matching — plain ILIKE contains/prefix only.
- Fixing the existing per-keystroke fetch behavior on the products/categories list pages
  (pre-existing, out of scope).
- Any other Phase 2 item (time-range toggle, fulfilment-rate stat, CSV import, dark theme).
