# Orders Daily Summary (Time-Series Chart) — Design

**Status:** Approved (section-by-section, 2026-07-11)
**Repos affected:** `walmal` (backend, new endpoint), `walmal-admin` (frontend, new dashboard chart)

## Context

`walmal-admin`'s Ops Dashboard redesign (Phase 1, shipped 2026-07-11) deliberately deferred a
time-series orders/revenue chart to Phase 2, because no aggregate endpoint existed — the
dashboard could only crunch the last 100 raw orders client-side, which isn't a real trend line.
This spec covers that Phase 2 item: a backend daily-summary endpoint and the frontend chart that
consumes it.

## Goal

Add a 30-day daily order-count + revenue trend chart to the `walmal-admin` dashboard, backed by a
new admin-only aggregate endpoint in `walmal`.

## Backend design (`walmal`, `walmal-order` module)

### Endpoint

`GET /api/v1/orders/admin/daily-summary`

- Added to `OrderController.java`, alongside the existing `/admin` list endpoint.
- `@PreAuthorize("hasAnyRole('ADMIN','STAFF')")` — same gate as `/orders/admin`.
- No query parameters. Fixed 30-day window (today − 29 days through today, inclusive).

### Response

Wrapped in the existing `ApiResponse<T>` convention:

```json
{
  "success": true,
  "data": [
    { "date": "2026-06-12", "orderCount": 4, "revenue": 812.50, "currency": "USD" },
    { "date": "2026-06-13", "orderCount": 0, "revenue": 0.00, "currency": "USD" },
    { "date": "2026-07-11", "orderCount": 9, "revenue": 2199.98, "currency": "USD" }
  ]
}
```

- Exactly 30 entries, ascending by date, zero-filled for days with no orders.
- `orderCount`: all order statuses.
- `revenue`: sum of `totalAmount` for `FULFILLED` orders only — matches the existing "Revenue
  Today" stat card's definition, so the new chart doesn't contradict what's already on screen.
- Day boundaries computed in **UTC** (orders store `Instant`; UTC avoids server-timezone
  ambiguity — there is no `orderDate` field, only `createdAt`/`updatedAt`).
- `currency`: this is a single-currency store in practice. Use the first currency seen among
  `FULFILLED` orders in the 30-day window; default to `"USD"` if there are none (matches the
  same fallback already used for `revenueCurrency` in `dashboard/index.tsx`).

### Implementation strategy (Option C — chosen over pushing aggregation into SQL)

The codebase has **no existing GROUP BY / date-aggregation precedent anywhere** (confirmed via
repo-wide search before this design was written) — this endpoint establishes the first one.
Three options were considered:

- **Option A** — JPQL `GROUP BY` with `FUNCTION('date_trunc', 'day', createdAt)` at the DB level.
  More efficient, but a genuinely new JPQL style for this codebase, and zero-filling missing days
  still has to happen in Java regardless.
- **Option B** — native SQL with Postgres `generate_series` to zero-fill in the DB. Most
  "correct" for a real analytics endpoint, but the biggest new pattern (raw SQL, no ORM) and
  nothing else in the repo does this.
- **Option C (chosen)** — fetch the 30-day window with a lightweight JPQL constructor-expression
  projection (`createdAt`, `totalAmount`, `currency`, `status` only — not full entities), then
  group + zero-fill + sum in the service layer, in plain Java.

Option C was chosen because it matches how this codebase already works (manual DTO
mapping/aggregation in service impls, no raw SQL anywhere) and is trivial to unit-test without a
database. At this store's current order volume, a 30-day fetch is cheap. If order volume ever
grows enough that this matters, Option A is the natural next step and this design's DTO/response
shape doesn't need to change to get there — only the repository/service internals would.

**This is the first GROUP BY / date-aggregation pattern anywhere in the codebase** (confirmed —
no `GROUP BY` exists in any Java module today). It is not, however, the first JPQL
constructor-expression projection: `walmal-pos`'s `PosSaleRepository` already uses `SELECT new
com.walmal.pos.application.dto.PosSyncConflictDto(...)` — that's useful prior art to model the
new `OrderTimeseriesRow` projection query on. Document the *aggregation* approach (JPQL
projection + Java-side date bucketing) in the backend KB as a precedent other modules can follow,
scoped accurately to "first aggregate/GROUP BY-equivalent query," not "first projection query."

Concretely:

1. New repository method on `OrderRepository`:
   ```java
   @Query("SELECT new com.walmal.order.application.dto.OrderTimeseriesRow(o.createdAt, o.totalAmount, o.currency, o.status) " +
          "FROM Order o WHERE o.createdAt >= :cutoff")
   List<OrderTimeseriesRow> findForDailySummary(@Param("cutoff") Instant cutoff);
   ```
   `OrderTimeseriesRow` is a small new record/DTO in `application/dto/`, not a JPA entity.

2. New method on `OrderAdminService` (it currently has only 2 methods — `listAllOrders`,
   `updateStatus` — so it has room for this) that:
   - Computes `cutoff = now.truncatedTo(DAYS).minus(29, DAYS)` (UTC).
   - Buckets the fetched rows by `row.createdAt().atZone(ZoneOffset.UTC).toLocalDate()`.
   - Builds the 30 consecutive `LocalDate`s from `cutoff`'s date through today, zero-filling any
     date with no rows.
   - For each date: `orderCount = rows.size()`; `revenue = sum(totalAmount where status ==
     FULFILLED)`; `currency` = first `FULFILLED` row's currency in the whole window, or `"USD"`.
   - This grouping/math logic is pure (no DB calls once the rows are fetched) — straightforward
     to unit test directly.

3. Controller method maps the service result to `DailyOrderSummaryDto` records and wraps in
   `ApiResponse.ok(...)`.

No new database migration — this only queries the existing `order_orders` table.

## Frontend design (`walmal-admin`)

### Data fetching

New `dailySummaryResult = useAsyncData(...)` in `src/pages/dashboard/index.tsx`, following the
exact pattern already used for `ordersResult`/`inventoryResult`/`terminalsResult`/
`notificationsResult`/`syncConflictsResult`: `apiClient.get(`${BASE}/orders/admin/daily-summary`)`
+ `unwrap()`. Gated on the existing `showOrders` permission check — no new `useCan` call needed.

### Pure logic

New `src/pages/dashboard/daily-summary.ts` — shapes the API response into what Recharts consumes
and formats date labels (`"2026-07-11"` → `"Jul 11"` for the X-axis). This is genuinely testable
logic (date formatting, empty-array handling), so — consistent with the Phase 1 convention
(`attention.ts`, `filters.ts`, `list-helpers.ts`) — it gets its own file + unit test rather than
being inlined into the page component.

### Chart card

New full-width `<Card>` in its own grid row, added above or below the existing
Orders-by-Status/Stock-Health row (that row is untouched). Title: "Orders & Revenue (Last 30
Days)". Recharts `<LineChart>`:
- `<XAxis dataKey="date">` (formatted short date labels from `daily-summary.ts`)
- Two `<YAxis>`: `yAxisId="orders"` (left), `yAxisId="revenue"` (right)
- Two `<Line>`s: order count and revenue, distinct colors consistent with existing chart color
  conventions
- `<Tooltip>`, `<Legend>`

### Loading/error state

Reuses the shared `Skeleton`/`WidgetError` components from `src/pages/dashboard/widgets.tsx`
(extracted during Phase 1's review process) — same loading/error treatment as every other
dashboard widget, no new pattern.

## Testing plan

### Backend (`walmal`)

- **Unit test** for the grouping/zero-fill/sum service logic (no DB): empty-orders case,
  multi-day spread, multiple orders same day, non-`FULFILLED` orders excluded from revenue but
  still counted in `orderCount`, orders outside the 30-day cutoff excluded, currency fallback to
  `"USD"` when zero `FULFILLED` orders in the window.
- **`@WebMvcTest`** for the controller: auth (403 for roles other than ADMIN/STAFF), response
  shape — following `OrderControllerTest`'s existing pattern.
- **Integration test** (`@Tag("integration")`, Testcontainers real Postgres): this is the first
  aggregation/date-bucketing query in `walmal-order` (the JPQL projection style itself has prior
  art in `walmal-pos`'s `PosSaleRepository`, but the grouping/zero-fill logic built on top of it
  is new), so prove the whole pipeline actually executes correctly against real Postgres, not
  just mocked. Seed a few orders with known dates/statuses/amounts, assert correct counts/revenue.

### Frontend (`walmal-admin`)

- **Unit test** for `daily-summary.ts` (date formatting, empty-array handling).
- **E2E smoke test**: assert the "Orders & Revenue" chart card renders without error on dashboard
  load. Do NOT assert exact chart values — seeded test data (V12 migration) likely doesn't span
  30 days of order history, so a value-correctness e2e assertion would be flaky/meaningless. This
  matches Phase 1's smoke-only e2e philosophy.

## KB updates (both repos, same session — this is a new cross-repo contract)

- `walmal`'s own KB — document the new endpoint, and explicitly document the JPQL-projection +
  Java-side-bucketing approach as a precedent for future aggregate endpoints (this is the first
  one), so the next similar feature doesn't have to re-derive the decision from scratch.
- `walmal-admin/docs/kb/architecture.md` — document the new chart/fetch/pure-logic file.
- `walmal/docs/kb/SYSTEM.md` — new endpoint is a cross-repo contract change, must be added here
  per AGENTS.md's maintenance rule.

## Explicitly out of scope

- Configurable date ranges or granularity (fixed 30-day daily buckets only, per approved design).
- Reviving the dashboard's dead Today/7d/30d time-range toggle (separate, smaller follow-up if
  wanted later — this endpoint doesn't preclude it, but doesn't build it either).
- Any other Phase 2 item (category stock-health rollup, global search, fulfilment-rate stat, CSV
  import, dark theme) — each gets its own design when picked up.
