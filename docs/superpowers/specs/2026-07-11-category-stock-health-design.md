# Category-Level Stock-Health Rollup — Design

**Status:** Approved (section-by-section, 2026-07-11)
**Repos affected:** `walmal` (backend, new endpoint + new cross-module query method), `walmal-admin` (frontend, replaces the dashboard's global Stock Health chart, adds a Categories list product-count column)

## Context

Phase 1 of the Ops Dashboard redesign deliberately left two things out because no backend rollup existed:
- The dashboard's Stock Health chart is global-only (OK/Low/Critical counts across all inventory), with no category breakdown, even though the original mockup called for one.
- The Categories list page has no product-count column at all — accurately counting products per category was explicitly deferred to "needs a backend rollup."

This spec covers both, backed by a single new endpoint.

## Architectural constraints discovered during research

- **This codebase has a hard rule (ADR-4) against one module's JPA layer JOINing another module's database tables**, even though everything lives in one physical Postgres database. `walmal-inventory` and `walmal-product` (which owns `Category`) are strictly separate at the persistence layer.
- **However, this is a single deployable Spring Boot application** (`walmal-app` aggregates every module into one JVM/one process) — "modules" are Maven/package boundaries, not network boundaries. The established, already-in-production pattern for cross-module reads/writes is **direct in-process interface injection** (e.g. `walmal-order` and `walmal-pos` already inject and call `walmal-inventory`'s `InventoryReservationService` directly, no HTTP, no event round-trip). This is explicitly documented in ADR-4 as the intended pattern.
- Therefore: this feature needs a **new read-only batch method on `walmal-inventory`'s existing `InventoryQueryService`** (already the established cross-module read interface, currently only exposing single-variant lookups), called in-process by a new orchestrating service in `walmal-product`.

## Backend design (`walmal`)

### New inventory-side method

`InventoryQueryService` (interface, `walmal-inventory/.../application/InventoryQueryService.java`) gets a new method:

```java
List<VariantStockHealth> getStockHealthByVariantIds(List<UUID> variantIds);
```

- `VariantStockHealth` — new record: `(UUID variantId, UUID locationId, StockHealthStatus status)`.
- `StockHealthStatus` — new enum: `OK`, `LOW`, `CRITICAL`.
- Classification (this becomes the backend's first source of truth for this logic — previously it only existed client-side in `walmal-admin`): `availableQuantity <= lowStockThreshold` → `CRITICAL`; `lowStockThreshold < availableQuantity <= lowStockThreshold * 2` → `LOW`; else → `OK`.
- Backed by a new `InventoryStockRepository.findByVariantIdIn(List<UUID> variantIds)` — no existing batch/`IN (...)` query precedent in this repository; this is a genuinely new addition, following the existing single-variant query patterns' style.
- This method stays entirely within `walmal-inventory`'s own schema — it takes bare `UUID`s (no product/category knowledge needed) and returns per-row classifications. No cross-module join.
- **No caching**: existing `InventoryQueryService` methods use `CacheService` (30-60s TTLs) for the POS hot path; `getStockHealthByVariantIds` deliberately skips caching — it's a lower-frequency admin dashboard read, not worth the added invalidation complexity for a batch query whose result set varies by caller.
- Update `InventoryQueryService`'s class-level Javadoc (currently framed as "consumed by the POS module") to also mention the new product-module consumer, so it doesn't go stale.

### New product-side orchestration + endpoint

New endpoint: `GET /api/v1/product/categories/stock-health`

- Added to `ProductController.java` (category endpoints already live here; there's no separate `CategoryController`).
- `@PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")` — the union of roles that can see either consuming page today (dashboard Stock Health chart: ADMIN + WAREHOUSE_MANAGER; Categories list: broader catalog-management audience, ADMIN + STAFF). Flagged as a judgment call during design review — if a tighter split is wanted later, this can be split into two endpoints without changing the underlying query logic.
- New orchestrating service method (in a new or existing product-side service — decide exact placement at implementation time) that:
  1. Runs **one** query against `walmal-product`'s own tables (`Category` LEFT JOIN `Product` LEFT JOIN `ProductVariant`, all within this module's schema — not a cross-module join) returning flat `(categoryId, categoryName, productId, variantId)` rows for every category/product/variant, with `productId`/`variantId` nullable in the result. **Must be a LEFT JOIN, not an INNER JOIN**: a product with zero variants is a real, reachable state in this codebase (`ProductVariant` is optional per `Product`'s own domain model, no API-layer rule requires at least one), and an inner join would silently exclude such products from `productCount` — defeating the entire point of this feature, since accurate per-category product counting is half of what Phase 1 deferred. Variant-less products must still be counted in `productCount`, just contribute nothing to the health tallies.
  2. Extracts the distinct non-null `variantId` list across ALL categories and calls `inventoryQueryService.getStockHealthByVariantIds(...)` **exactly once** — not once per category (avoids N+1 across the module boundary).
  3. Aggregates in Java: groups the flat rows by `categoryId`; for each category, `productCount` = count of distinct non-null `productId`s (a variant-less product still counts once here), and `okCount`/`lowCount`/`criticalCount` = tally of the matched `VariantStockHealth` results whose `variantId` belongs to that category (rows with a null `variantId` contribute nothing to these tallies).
  4. Categories with **zero** products/variants still appear in the result with `productCount: 0` and all health counts `0` — not omitted.

### Response shape

```json
{
  "success": true,
  "data": [
    { "categoryId": "...", "categoryName": "Electronics", "productCount": 42, "okCount": 380, "lowCount": 12, "criticalCount": 3 },
    { "categoryId": "...", "categoryName": "Grocery", "productCount": 18, "okCount": 90, "lowCount": 0, "criticalCount": 0 }
  ]
}
```

- **No hierarchy rollup** (per design decision): a parent category's numbers reflect only its own directly-assigned products, never descendants'. Matches how the Categories list page already displays categories as a flat table.
- `okCount + lowCount + criticalCount` per category = total stock ROWS (variant+location pairs) for that category's products — same unit the existing global Stock Health chart already uses, so summing across all categories reproduces the same totals the old global chart showed (a true breakdown, not a different metric).
- **Ordering**: categories are returned sorted alphabetically by `categoryName` — gives the dashboard's horizontal bar chart a stable, predictable Y-axis order without the frontend needing to sort.
- **Scale note**: dev/seed data has 6 categories; this design (no pagination, one flat horizontal-bar-per-category chart) is sized for a small-to-moderate category count. If the category list ever grows large enough to make a single stacked bar chart unreadable, that's a frontend display concern to revisit then — not a reason to add pagination to this endpoint now.

### No new database migration — this only queries existing tables in both modules.

## Frontend design (`walmal-admin`)

### Dashboard — replacing the global Stock Health chart

The existing client-side `stockBarData` computation (from raw `inventoryResult`, 3 fixed OK/Low/Critical bars) is replaced by a new fetch to `GET /product/categories/stock-health`, feeding a **horizontal stacked bar chart**: one bar per category (Y-axis = category name), each bar segmented into OK (green) / Low (yellow) / Critical (red) counts (X-axis, stacked). Reuses the existing `layout="vertical"` `<BarChart>` pattern already on this page — the change is `stackId="health"` across three `<Bar>` series instead of one flat series. Same card slot, same `showStockHealthChart` permission gate (unchanged), same color convention already used elsewhere on the dashboard. Loading/error follow the established `Skeleton`/`WidgetError` pattern.

### Categories list — real product-count column

The Categories page makes its own fetch to the same `GET /product/categories/stock-health` endpoint (cheap — a small, flat, unpaginated per-category list), builds a `Map<categoryId, productCount>` client-side, and joins it into the existing category rows to populate a new "Products" column, replacing the placeholder Phase 1 explicitly omitted. If the fetch fails, or a category's ID isn't present in the map (e.g. the endpoint is unreachable, or a permission edge case), the column shows `"—"` for that row rather than blocking the rest of the table.

### Pure logic

Following the established Phase 1/2 convention, two small colocated pure functions get their own file + unit test:
- Dashboard: OK/Low/Critical rows → Recharts stacked-bar-data shape.
- Categories: the flat category-stock-health response → a `Map<categoryId, productCount>` (or similar lookup shape) for joining into the table.

## Testing plan

### Backend (`walmal`)

- **Unit test**: stock-health classification boundary logic (`available == threshold`, `available == threshold*2`, well above/below both) — this is new backend logic (previously frontend-only), deserves careful boundary coverage.
- **Unit test**: the new batch query method (mocked repository) — correct per-row classification and grouping.
- **Unit test**: the new `walmal-product` orchestration service (mocked `InventoryQueryService`) — aggregation logic: productCount tally, health-count tally per category, correct handling of a category with zero products/variants (must still appear with all-zero counts, not be omitted).
- **`@WebMvcTest`**: the new controller endpoint — auth boundary (ADMIN/STAFF/WAREHOUSE_MANAGER allowed, other roles 403), response shape.
- **Integration test** (Testcontainers, real Postgres): this feature introduces two new repository queries (inventory's `findByVariantIdIn`, product's flat category/product/variant projection) plus the in-process cross-module call between them — worth proving the whole pipeline end-to-end against a real database, matching the bar set by the daily-summary feature's integration test.

### Frontend (`walmal-admin`)

- **Unit tests**: the two new pure functions (stacked-bar-data shaping; category→count map builder).
- **E2E smoke tests**: dashboard shows the per-category stock health chart (category labels visible, not exact counts asserted); Categories list shows a populated "Products" column (a number renders for at least one row, not asserting exact seed-data counts).

## KB updates (both repos, same session — new cross-repo contract)

- `walmal`'s KB — document the new endpoint, the new `InventoryQueryService.getStockHealthByVariantIds` batch method, and the aggregation approach (flat single-module queries + one batched in-process cross-module call + Java-side grouping) as a second example of this codebase's now-established "fetch flat, aggregate in Java" rollup pattern (the daily-summary endpoint was the first).
- `walmal/docs/kb/SYSTEM.md` — new cross-repo contract entry.
- `walmal-admin/docs/kb/architecture.md` — document the replaced dashboard chart and the new Categories list column.

## Explicitly out of scope

- Hierarchy rollup (parent categories including descendants' products) — explicitly decided against; each category is independent.
- Per-variant (rather than per-stock-row) health counting — explicitly decided against, to keep category totals summing to the existing global chart's numbers.
- Splitting the endpoint's auth gate into two tighter, page-specific endpoints — flagged as a possible future refinement, not built now.
- Any other Phase 2 item (global search, time-range toggle, fulfilment-rate stat, CSV import, dark theme) — each gets its own design when picked up.
