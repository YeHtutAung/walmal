# Category-Level Stock-Health Rollup — Design

**Status:** Approved (section-by-section, 2026-07-11); backend module ownership corrected during plan-writing research (see "Architectural constraints" — endpoint moved from `walmal-product` to `walmal-inventory` to avoid a Maven dependency cycle), re-approved 2026-07-11.
**Repos affected:** `walmal` (backend — new endpoint in `walmal-inventory`, new query method on `walmal-product`'s existing `ProductCatalogService`), `walmal-admin` (frontend, replaces the dashboard's global Stock Health chart, adds a Categories list product-count column)

## Context

Phase 1 of the Ops Dashboard redesign deliberately left two things out because no backend rollup existed:
- The dashboard's Stock Health chart is global-only (OK/Low/Critical counts across all inventory), with no category breakdown, even though the original mockup called for one.
- The Categories list page has no product-count column at all — accurately counting products per category was explicitly deferred to "needs a backend rollup."

This spec covers both, backed by a single new endpoint.

## Architectural constraints discovered during research

- **This codebase has a hard rule (ADR-4) against one module's JPA layer JOINing another module's database tables**, even though everything lives in one physical Postgres database. `walmal-inventory` and `walmal-product` (which owns `Category`) are strictly separate at the persistence layer.
- **However, this is a single deployable Spring Boot application** (`walmal-app` aggregates every module into one JVM/one process) — "modules" are Maven/package boundaries, not network boundaries. The established, already-in-production pattern for cross-module reads/writes is **direct in-process interface injection** (e.g. `walmal-order` and `walmal-pos` already inject and call `walmal-inventory`'s `InventoryReservationService` directly, no HTTP, no event round-trip). This is explicitly documented in ADR-4 as the intended pattern.
- **Correction discovered during plan-writing research (this section originally said the opposite):** `walmal-inventory` already has a compile-scope Maven dependency on `walmal-product` (for the existing `ProductCatalogService` interface, used for variant validation). The dependency is **one-directional**: `walmal-product` has no dependency on `walmal-inventory`. Any design that has `walmal-product` inject something from `walmal-inventory` would create a Maven reactor cycle (`inventory→product→inventory`) and simply fail to build. **Therefore this feature must be owned by `walmal-inventory`, not `walmal-product`** — the data flow runs in the already-established direction: inventory calls into product (via a new method on the existing `ProductCatalogService` interface), not the reverse.

## Backend design (`walmal`)

### New product-side method (on an existing interface)

`ProductCatalogService` (`walmal-product/.../application/ProductCatalogService.java` — already injected into `walmal-inventory` today, e.g. for variant-active checks) gets one new method:

```java
List<CategoryProductVariantRow> getAllCategoryProductVariantMappings();
```

- `CategoryProductVariantRow` — new record: `(UUID categoryId, String categoryName, UUID productId, UUID variantId)`, with `productId`/`variantId` nullable.
- Implementation runs **one** query against `walmal-product`'s own tables (`Category` LEFT JOIN `Product` LEFT JOIN `ProductVariant`, all within this module's schema — not a cross-module join) returning a flat row per category/product/variant combination. **Must be a LEFT JOIN, not an INNER JOIN**: a product with zero variants is a real, reachable state in this codebase (`ProductVariant` is optional per `Product`'s own domain model, no API-layer rule requires at least one), and an inner join would silently exclude such products from the eventual product count — defeating the entire point of this feature, since accurate per-category product counting is half of what Phase 1 deferred. Variant-less products must still appear (with a null `variantId`).
- This method returns everything in one call, no pagination (see Scale note below) — it's a read used only by the new inventory-side rollup, not a general-purpose paginated product API.
- No auth annotation needed here — this is an internal, in-process method call between modules, not a REST endpoint; the auth gate lives on the public endpoint below.
- Update `ProductCatalogService`'s class-level Javadoc (currently says "consumed by Order and POS modules") to also mention Inventory — Inventory already injects this interface in production code today (`InventoryReservationServiceImpl` calls `isVariantActive`), so this is pre-existing doc drift, not something this feature introduces, but adding the new method is a good, low-cost point to fix it.

### New inventory-side orchestration + endpoint

New endpoint: `GET /api/v1/inventory/categories/stock-health`

- Lives in a **new controller file** (e.g. `CategoryStockHealthController.java`) in `walmal-inventory`'s `api` package, not appended to `InventoryStockController.java` — confirmed during plan-writing research that the existing controller's `/stock/{variantId}/...` resource shape and its 3 injected services don't fit a category-aggregation endpoint, and this module already splits controllers by resource shape (`InventoryLocationController`, `InventoryMovementController` are already separate files rather than piled onto `InventoryStockController`).
- `@PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")` — the union of roles that can see either consuming page today (dashboard Stock Health chart: ADMIN + WAREHOUSE_MANAGER; Categories list: broader catalog-management audience, ADMIN + STAFF). Flagged as a judgment call during design review — if a tighter split is wanted later, this can be split into two endpoints without changing the underlying query logic.
- New orchestrating service method (in a new or existing inventory-side service — decide exact placement at implementation time) that:
  1. Calls `productCatalogService.getAllCategoryProductVariantMappings()` **exactly once** — the already-established, already-injected cross-module call.
  2. Extracts the distinct non-null `variantId` list and queries `InventoryStockRepository.findByVariantIdIn(...)` (new repository method — no existing batch/`IN (...)` query precedent in this repository, following the existing single-variant query patterns' style) **exactly once** — not once per category. This is a plain repository call within inventory's own module; it does not need to go through a new public `InventoryQueryService` method, since the orchestration and the repository both live in the same module now.
  3. Classifies each returned stock row as `OK` / `LOW` / `CRITICAL` (this becomes the backend's first source of truth for this logic — previously it only existed client-side in `walmal-admin`): `availableQuantity <= lowStockThreshold` → `CRITICAL`; `lowStockThreshold < availableQuantity <= lowStockThreshold * 2` → `LOW`; else → `OK`.
  4. Aggregates in Java: groups the product-side flat rows by `categoryId`; for each category, `productCount` = count of distinct non-null `productId`s (a variant-less product still counts once here), and `okCount`/`lowCount`/`criticalCount` = tally of the classified stock rows whose `variantId` belongs to that category (rows with a null `variantId`, or a `variantId` with no matching stock rows, contribute nothing to these tallies).
  5. Categories with **zero** products/variants still appear in the result with `productCount: 0` and all health counts `0` — not omitted.
- **No caching** on this endpoint or its underlying calls: it's a lower-frequency admin dashboard read, not worth the added invalidation complexity for a batch query whose result set varies by caller.

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

The existing client-side `stockBarData` computation (from raw `inventoryResult`, 3 fixed OK/Low/Critical bars) is replaced by a new fetch to `GET /inventory/categories/stock-health`, feeding a **horizontal stacked bar chart**: one bar per category (Y-axis = category name), each bar segmented into OK (green) / Low (yellow) / Critical (red) counts (X-axis, stacked). Reuses the existing `layout="vertical"` `<BarChart>` pattern already on this page — the change is `stackId="health"` across three `<Bar>` series instead of one flat series. Same card slot, same `showStockHealthChart` permission gate (unchanged), same color convention already used elsewhere on the dashboard. Loading/error follow the established `Skeleton`/`WidgetError` pattern.

### Categories list — real product-count column

The Categories page makes its own fetch to the same `GET /inventory/categories/stock-health` endpoint (cheap — a small, flat, unpaginated per-category list), builds a `Map<categoryId, productCount>` client-side, and joins it into the existing category rows to populate a new "Products" column, replacing the placeholder Phase 1 explicitly omitted. If the fetch fails, or a category's ID isn't present in the map (e.g. the endpoint is unreachable, or a permission edge case), the column shows `"—"` for that row rather than blocking the rest of the table.

### Pure logic

Following the established Phase 1/2 convention, two small colocated pure functions get their own file + unit test:
- Dashboard: OK/Low/Critical rows → Recharts stacked-bar-data shape.
- Categories: the flat category-stock-health response → a `Map<categoryId, productCount>` (or similar lookup shape) for joining into the table.

## Testing plan

### Backend (`walmal`)

- **Unit test** (`walmal-product`): the new `getAllCategoryProductVariantMappings()` query — correct LEFT JOIN behavior, confirming a variant-less product still appears (with null `variantId`), a category with zero products still appears.
- **Unit test** (`walmal-inventory`): stock-health classification boundary logic (`available == threshold`, `available == threshold*2`, well above/below both) — this is new backend logic (previously frontend-only), deserves careful boundary coverage.
- **Unit test** (`walmal-inventory`): the new orchestration service (mocked `ProductCatalogService` + mocked `InventoryStockRepository`) — aggregation logic: productCount tally, health-count tally per category, correct handling of a category with zero products/variants (must still appear with all-zero counts, not be omitted).
- **`@WebMvcTest`** (`walmal-inventory`): the new controller endpoint — auth boundary (ADMIN/STAFF/WAREHOUSE_MANAGER allowed, other roles 403), response shape.
- **Two single-module integration tests instead of one cross-module test** (Testcontainers, real Postgres each), decided during plan-writing research: `walmal-inventory`'s existing `InventoryTestApplication` has zero entity/repository scan reach into `walmal-product` today (confirmed — no `@EntityScan`/`@EnableJpaRepositories` overrides), and `ProductCatalogServiceImpl` has 6 constructor dependencies; wiring a real cross-module Spring context for one test would be fragile, novel test infrastructure for marginal extra coverage. The DI wiring between `walmal-inventory` and `ProductCatalogService` is *already* exercised by existing production code (`InventoryReservationServiceImpl` already injects and calls it) — that part isn't new and doesn't need re-proving. So instead:
  - **Integration test in `walmal-product`**: proves `getAllCategoryProductVariantMappings()`'s LEFT JOIN query returns correct results against real Postgres (variant-less products included with null `variantId`, empty categories included), entirely within `walmal-product`'s own existing integration-test infrastructure.
  - **Integration test in `walmal-inventory`**: proves `findByVariantIdIn(...)` returns correct results against real Postgres, entirely within `walmal-inventory`'s own existing integration-test infrastructure.
  - Combined with the orchestration service's unit test (mocked `ProductCatalogService` + mocked `InventoryStockRepository`, proving the aggregation logic), this covers everything the original single cross-module test would have — real-DB correctness for each new query, and correct aggregation logic — without new, brittle test-only Spring wiring.

### Frontend (`walmal-admin`)

- **Unit tests**: the two new pure functions (stacked-bar-data shaping; category→count map builder).
- **E2E smoke tests**: dashboard shows the per-category stock health chart (category labels visible, not exact counts asserted); Categories list shows a populated "Products" column (a number renders for at least one row, not asserting exact seed-data counts).

## KB updates (both repos, same session — new cross-repo contract)

- `walmal`'s KB — document the new endpoint (owned by `walmal-inventory`), the new `ProductCatalogService.getAllCategoryProductVariantMappings()` method, and the aggregation approach (flat single-module queries + one in-process cross-module call in the already-established inventory→product direction + Java-side grouping) as a second example of this codebase's now-established "fetch flat, aggregate in Java" rollup pattern (the daily-summary endpoint was the first). Also worth a one-line note on why this module ownership was chosen (the one-directional `inventory→product` Maven dependency) so a future engineer doesn't try to "fix" it by moving the endpoint to `walmal-product`.
- `walmal/docs/kb/SYSTEM.md` — new cross-repo contract entry.
- `walmal-admin/docs/kb/architecture.md` — document the replaced dashboard chart and the new Categories list column.

## Explicitly out of scope

- Hierarchy rollup (parent categories including descendants' products) — explicitly decided against; each category is independent.
- Per-variant (rather than per-stock-row) health counting — explicitly decided against, to keep category totals summing to the existing global chart's numbers.
- Splitting the endpoint's auth gate into two tighter, page-specific endpoints — flagged as a possible future refinement, not built now.
- Any other Phase 2 item (global search, time-range toggle, fulfilment-rate stat, CSV import, dark theme) — each gets its own design when picked up.
