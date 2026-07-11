# Category-Level Stock-Health Rollup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new endpoint (`GET /api/v1/inventory/categories/stock-health`) that returns, per category, a product count and an OK/Low/Critical stock-health breakdown — then use it to replace the dashboard's global Stock Health chart with a per-category stacked bar chart, and to add a real product-count column to the Categories list page.

**Architecture:** `walmal-inventory` owns the new endpoint (it already has a one-directional Maven dependency on `walmal-product`, so the data flow runs inventory→product, not the reverse — this direction was corrected during plan-writing research after the original spec had it backwards and would have created a Maven reactor cycle). A new method on `walmal-product`'s existing `ProductCatalogService` interface returns a flat, LEFT-JOINed `(categoryId, categoryName, productId, variantId)` row set. Inventory's new orchestrating service calls that once, batch-queries its own stock table once, classifies each row OK/LOW/CRITICAL (a new domain method on `InventoryStock`), and aggregates per category in Java — the same "fetch flat, aggregate in Java" pattern established by the daily-summary feature.

**Tech Stack:** Backend — Spring Boot, Spring Data JPA, JUnit 5 + Mockito + Testcontainers. Frontend — React, Refine, Recharts (stacked `<Bar>` — new to this codebase; existing charts are single-series), Vitest.

**Spec:** `docs/superpowers/specs/2026-07-11-category-stock-health-design.md` (same repo) — read for full rationale, including the module-ownership correction and testing-strategy refinement, both made during plan-writing research.

---

## Repos and worktrees

Spans two sibling repos. **Tasks 1–6 execute against `walmal`; Tasks 7–11 execute against `walmal-admin`.** Set up two separate worktrees via `using-git-worktrees`. Finish and merge the `walmal` side before starting `walmal-admin` (Task 11's e2e test needs a live endpoint to hit; Tasks 7 and 9, the pure-logic tasks, have no such dependency but for simplicity the whole frontend side follows the backend, matching the daily-summary plan's approach).

---

## File Structure

**New files (`walmal/walmal-product/`):**
- `src/main/java/com/walmal/product/application/dto/CategoryProductVariantRow.java`
- `src/test/java/com/walmal/product/infrastructure/CategoryRepositoryStockHealthIntegrationTest.java` (exact name/location TBD — follow this module's existing integration test file naming)

**Modified files (`walmal/walmal-product/`):**
- `src/main/java/com/walmal/product/application/ProductCatalogService.java`
- `src/main/java/com/walmal/product/application/impl/ProductCatalogServiceImpl.java`
- `src/main/java/com/walmal/product/infrastructure/CategoryRepository.java`

**New files (`walmal/walmal-inventory/`):**
- `src/main/java/com/walmal/inventory/domain/StockHealthStatus.java`
- `src/main/java/com/walmal/inventory/application/dto/CategoryStockHealthDto.java` (exact package TBD — follow existing DTO location convention in this module)
- `src/main/java/com/walmal/inventory/application/CategoryStockHealthService.java` (interface)
- `src/main/java/com/walmal/inventory/application/impl/CategoryStockHealthServiceImpl.java`
- `src/main/java/com/walmal/inventory/api/CategoryStockHealthController.java`
- `src/test/java/com/walmal/inventory/domain/InventoryStockClassifyHealthTest.java` (or added to an existing `InventoryStock` domain test if one exists)
- `src/test/java/com/walmal/inventory/application/impl/CategoryStockHealthServiceImplTest.java`
- `src/test/java/com/walmal/inventory/api/CategoryStockHealthControllerTest.java`
- `src/test/java/com/walmal/inventory/infrastructure/InventoryStockFindByVariantIdInIntegrationTest.java` (exact name TBD)

**Modified files (`walmal/walmal-inventory/`):**
- `src/main/java/com/walmal/inventory/domain/InventoryStock.java`
- `src/main/java/com/walmal/inventory/infrastructure/InventoryStockRepository.java`

**KB (both repos):**
- `walmal/docs/kb/architecture.md`, `walmal/docs/kb/SYSTEM.md`
- `walmal-admin/docs/kb/architecture.md`, `walmal-admin/docs/kb/testing.md`

**New files (`walmal-admin/src/`):**
- `pages/dashboard/category-stock-health.ts` + `.test.ts`
- `pages/dashboard/CategoryStockHealthChart.tsx`
- Addition to `pages/categories/list-helpers.ts` (+ its existing `.test.ts`)

**Modified files (`walmal-admin/`):**
- `src/pages/dashboard/index.tsx`
- `src/pages/categories/list.tsx`
- `tests/e2e/dashboard.spec.ts`, `tests/e2e/categories.spec.ts`

---

## Backend tasks (`walmal`)

### Task 1: Product-side flat query (`walmal-product`)

**Files:**
- Create: `src/main/java/com/walmal/product/application/dto/CategoryProductVariantRow.java`
- Modify: `src/main/java/com/walmal/product/infrastructure/CategoryRepository.java`
- Modify: `src/main/java/com/walmal/product/application/ProductCatalogService.java`
- Modify: `src/main/java/com/walmal/product/application/impl/ProductCatalogServiceImpl.java`

There is no meaningful pure logic to unit-test in this task (it's a query + a one-line delegation) — correctness is proven by Task 5's integration test. This task is: wire it correctly, confirm it compiles, defer proof to the integration test.

- [ ] **Step 1: Create the DTO**

```java
// src/main/java/com/walmal/product/application/dto/CategoryProductVariantRow.java
package com.walmal.product.application.dto;

import java.util.UUID;

/**
 * Flat projection row for the category-level stock-health rollup (consumed by walmal-inventory
 * via ProductCatalogService). productId/variantId are nullable — a category can have zero
 * products, and a product can have zero variants; both must still appear here (LEFT JOIN, not
 * INNER JOIN) so callers can count them correctly.
 */
public record CategoryProductVariantRow(UUID categoryId, String categoryName, UUID productId, UUID variantId) {}
```

- [ ] **Step 2: Add the LEFT JOIN query to `CategoryRepository`**

```java
// add to CategoryRepository.java
@Query("SELECT new com.walmal.product.application.dto.CategoryProductVariantRow(" +
       "c.id, c.name, p.id, v.id) " +
       "FROM Category c " +
       "LEFT JOIN Product p ON p.category = c " +
       "LEFT JOIN p.variants v")
List<CategoryProductVariantRow> findCategoryProductVariantRows();
```

Add the `CategoryProductVariantRow` and `List`/`Query` imports. This is the module's first `LEFT JOIN ... ON` style query — read `ProductVariantRepository.java`'s existing `@Query` (a simple `JOIN FETCH`) for this module's general `@Query` formatting convention, but the explicit `ON` clause here is new territory since `Category` has no direct mapped collection back to `Product`.

- [ ] **Step 3: Add the interface method to `ProductCatalogService`**

```java
// add to ProductCatalogService.java, alongside isVariantActive etc.
/**
 * Flat category → product → variant rows for every category, including categories with zero
 * products and products with zero variants (both appear with null downstream IDs). Used by
 * walmal-inventory to build a category-level stock-health rollup — see
 * docs/superpowers/specs/2026-07-11-category-stock-health-design.md.
 */
List<CategoryProductVariantRow> getAllCategoryProductVariantMappings();
```

Also update this interface's class-level Javadoc (currently says "consumed by Order and POS modules") to mention Inventory too — Inventory already injects this interface in production (`InventoryReservationServiceImpl` calls `isVariantActive`), so this is fixing pre-existing doc drift while you're in here, not scope creep.

- [ ] **Step 4: Implement it in `ProductCatalogServiceImpl`**

Add `CategoryRepository` as a 7th constructor parameter (current 6: `ProductVariantRepository`, `ProductRepository`, `ProductPriceRepository`, `ProductImageRepository`, `ProductImageStorageAdapter`, `CacheService`):

```java
// add to ProductCatalogServiceImpl.java
@Override
public List<CategoryProductVariantRow> getAllCategoryProductVariantMappings() {
    return categoryRepository.findCategoryProductVariantRows();
}
```

No caching (matches the "no caching" decision in the spec — this is a lower-frequency admin read, and every existing constructor-injection test for this class will need updating for the new 7th parameter, so keep the change itself minimal).

- [ ] **Step 5: Verify compilation and existing tests**

Run: `./mvnw -pl walmal-product -am compile`
Expected: BUILD SUCCESS.

Run: `./mvnw -pl walmal-product -am test`
Expected: existing `ProductCatalogServiceImplTest` (or equivalent) will likely FAIL to compile/instantiate due to the new constructor parameter — find it and add a `@Mock CategoryRepository categoryRepository` alongside its existing mocks, following that test class's existing Mockito convention. This is expected, necessary maintenance, not a new bug.

- [ ] **Step 6: Commit**

```bash
git add walmal-product/src/main/java/com/walmal/product/application/dto/CategoryProductVariantRow.java \
        walmal-product/src/main/java/com/walmal/product/infrastructure/CategoryRepository.java \
        walmal-product/src/main/java/com/walmal/product/application/ProductCatalogService.java \
        walmal-product/src/main/java/com/walmal/product/application/impl/ProductCatalogServiceImpl.java \
        walmal-product/src/test/java/com/walmal/product/application/ProductCatalogServiceImplTest.java
git commit -m "feat(product): add category-product-variant flat query for cross-module stock-health rollup"
```

(Confirmed real path — no `impl` subpackage in the test source tree, unlike the main source tree.)

---

### Task 2: Inventory-side stock classification (TDD)

**Files:**
- Modify: `src/main/java/com/walmal/inventory/domain/InventoryStock.java`
- Create: `src/main/java/com/walmal/inventory/domain/StockHealthStatus.java`
- Modify: `src/main/java/com/walmal/inventory/infrastructure/InventoryStockRepository.java`
- Test: a new or existing domain test file for `InventoryStock` (check if one already exists before creating `InventoryStockClassifyHealthTest.java`)

- [ ] **Step 1: Create the enum**

```java
// src/main/java/com/walmal/inventory/domain/StockHealthStatus.java
package com.walmal.inventory.domain;

public enum StockHealthStatus {
    OK, LOW, CRITICAL
}
```

- [ ] **Step 2: Write the failing test for classification**

```java
// test for InventoryStock.classifyHealth() — file location per Step 2 note above
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class InventoryStockClassifyHealthTest {

    private InventoryStock stockWith(int available, int threshold) {
        InventoryStock stock = new InventoryStock(/* construct per this entity's real constructor/builder — check InventoryStock.java for the actual instantiation pattern used elsewhere in this module's tests */);
        // set availableQuantity=available, lowStockThreshold=threshold via whatever this entity's
        // real test-construction convention is (builder, setters, or a test factory helper)
        return stock;
    }

    @Test
    void classifiesAsCritical_whenAvailableAtOrBelowThreshold() {
        assertThat(stockWith(10, 10).classifyHealth()).isEqualTo(StockHealthStatus.CRITICAL);
        assertThat(stockWith(5, 10).classifyHealth()).isEqualTo(StockHealthStatus.CRITICAL);
        assertThat(stockWith(0, 10).classifyHealth()).isEqualTo(StockHealthStatus.CRITICAL);
    }

    @Test
    void classifiesAsLow_whenAvailableBetweenThresholdAndDoubleThresholdInclusive() {
        assertThat(stockWith(11, 10).classifyHealth()).isEqualTo(StockHealthStatus.LOW);
        assertThat(stockWith(20, 10).classifyHealth()).isEqualTo(StockHealthStatus.LOW);
    }

    @Test
    void classifiesAsOk_whenAvailableAboveDoubleThreshold() {
        assertThat(stockWith(21, 10).classifyHealth()).isEqualTo(StockHealthStatus.OK);
        assertThat(stockWith(1000, 10).classifyHealth()).isEqualTo(StockHealthStatus.OK);
    }
}
```

Before writing this, read `InventoryStock.java` in full to find the real way this entity is constructed in existing tests in this module (check `InventoryQueryServiceImplTest.java`'s mock-construction style, or any existing `InventoryStock`-focused test) — adjust `stockWith(...)` to match reality rather than guessing a constructor signature.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -pl walmal-inventory -am test -Dtest=InventoryStockClassifyHealthTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `classifyHealth()` not defined.

- [ ] **Step 3: Implement it**

Add right after the existing `isExhausted()` domain guard method (before the getters/setters section):

```java
public StockHealthStatus classifyHealth() {
    if (this.availableQuantity <= this.lowStockThreshold) {
        return StockHealthStatus.CRITICAL;
    }
    if (this.availableQuantity <= this.lowStockThreshold * 2) {
        return StockHealthStatus.LOW;
    }
    return StockHealthStatus.OK;
}
```

This is the backend's first source of truth for this classification — it exactly matches the logic currently duplicated client-side in `walmal-admin/src/pages/dashboard/index.tsx` (`criticalStock`/`lowStock`/`okStock` filters), which Task 8 will delete once this endpoint replaces it.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -pl walmal-inventory -am test -Dtest=InventoryStockClassifyHealthTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (3 tests)

- [ ] **Step 5: Add the batch repository method**

```java
// add to InventoryStockRepository.java, near the existing findByVariantId
List<InventoryStock> findByVariantIdIn(List<UUID> variantIds);
```

Plain Spring Data derived query, no `@Query` needed (matches this repository's existing single-variant finder style). No unit test for this alone — correctness proven by Task 5's integration test.

- [ ] **Step 6: Verify compilation**

Run: `./mvnw -pl walmal-inventory -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add walmal-inventory/src/main/java/com/walmal/inventory/domain/InventoryStock.java \
        walmal-inventory/src/main/java/com/walmal/inventory/domain/StockHealthStatus.java \
        walmal-inventory/src/main/java/com/walmal/inventory/infrastructure/InventoryStockRepository.java \
        walmal-inventory/src/test/java/com/walmal/inventory/domain/InventoryStockClassifyHealthTest.java
git commit -m "feat(inventory): add stock-health classification and batch variant-ID lookup"
```

(Adjust the test file path to wherever it actually landed per Step 2's investigation.)

---

### Task 3: Orchestration service (TDD)

**Files:**
- Create: `src/main/java/com/walmal/inventory/api/dto/response/CategoryStockHealthDto.java` — confirmed during plan review: `walmal-inventory` has no `application/dto/` package anywhere today; every existing service-response DTO (`StockLevelResponse` and friends) lives under `api/dto/response/`, so this new DTO belongs there too, not in a new package. (Naming note: existing DTOs in that package end in `Response`, e.g. `StockLevelResponse` — `CategoryStockHealthDto` is used consistently throughout the rest of this plan document for this type, but consider renaming to `CategoryStockHealthResponse` to match the package's naming convention if you're not otherwise attached to the `Dto` suffix; either name works functionally, this plan just didn't rename every occurrence below.)
- Create: `src/main/java/com/walmal/inventory/application/CategoryStockHealthService.java`
- Create: `src/main/java/com/walmal/inventory/application/impl/CategoryStockHealthServiceImpl.java`
- Test: `src/test/java/com/walmal/inventory/application/impl/CategoryStockHealthServiceImplTest.java`

- [ ] **Step 1: Create the DTO**

```java
public record CategoryStockHealthDto(
        UUID categoryId, String categoryName, long productCount,
        long okCount, long lowCount, long criticalCount
) {}
```

- [ ] **Step 2: Write the failing test for the aggregation logic**

```java
// CategoryStockHealthServiceImplTest.java
@ExtendWith(MockitoExtension.class)
class CategoryStockHealthServiceImplTest {

    @Mock private ProductCatalogService productCatalogService;
    @Mock private InventoryStockRepository stockRepository;

    private CategoryStockHealthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CategoryStockHealthServiceImpl(productCatalogService, stockRepository);
    }

    @Test
    void returnsZeroedCategory_whenCategoryHasNoProducts() {
        UUID catId = UUID.randomUUID();
        when(productCatalogService.getAllCategoryProductVariantMappings()).thenReturn(List.of(
                new CategoryProductVariantRow(catId, "Empty", null, null)
        ));
        when(stockRepository.findByVariantIdIn(List.of())).thenReturn(List.of());

        List<CategoryStockHealthDto> result = service.getStockHealthByCategory();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(new CategoryStockHealthDto(catId, "Empty", 0, 0, 0, 0));
    }

    @Test
    void countsVariantLessProduct_towardProductCount_butNotHealthTallies() {
        UUID catId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productCatalogService.getAllCategoryProductVariantMappings()).thenReturn(List.of(
                new CategoryProductVariantRow(catId, "Cat", productId, null)
        ));
        when(stockRepository.findByVariantIdIn(List.of())).thenReturn(List.of());

        CategoryStockHealthDto dto = service.getStockHealthByCategory().get(0);

        assertThat(dto.productCount()).isEqualTo(1);
        assertThat(dto.okCount() + dto.lowCount() + dto.criticalCount()).isEqualTo(0);
    }

    @Test
    void tallliesHealthCountsPerCategory_fromMatchingStockRows() {
        UUID catId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        when(productCatalogService.getAllCategoryProductVariantMappings()).thenReturn(List.of(
                new CategoryProductVariantRow(catId, "Cat", productId, variantId)
        ));
        InventoryStock critical = mock(InventoryStock.class);
        when(critical.getVariantId()).thenReturn(variantId);
        when(critical.classifyHealth()).thenReturn(StockHealthStatus.CRITICAL);
        InventoryStock ok = mock(InventoryStock.class);
        when(ok.getVariantId()).thenReturn(variantId);
        when(ok.classifyHealth()).thenReturn(StockHealthStatus.OK);
        when(stockRepository.findByVariantIdIn(List.of(variantId))).thenReturn(List.of(critical, ok));

        CategoryStockHealthDto dto = service.getStockHealthByCategory().get(0);

        assertThat(dto.productCount()).isEqualTo(1);
        assertThat(dto.criticalCount()).isEqualTo(1);
        assertThat(dto.okCount()).isEqualTo(1);
        assertThat(dto.lowCount()).isEqualTo(0);
    }

    @Test
    void sortsCategoriesAlphabeticallyByName() {
        UUID catA = UUID.randomUUID();
        UUID catB = UUID.randomUUID();
        when(productCatalogService.getAllCategoryProductVariantMappings()).thenReturn(List.of(
                new CategoryProductVariantRow(catB, "Zebra", null, null),
                new CategoryProductVariantRow(catA, "Apple", null, null)
        ));
        when(stockRepository.findByVariantIdIn(List.of())).thenReturn(List.of());

        List<CategoryStockHealthDto> result = service.getStockHealthByCategory();

        assertThat(result).extracting(CategoryStockHealthDto::categoryName).containsExactly("Apple", "Zebra");
    }
}
```

Adjust `InventoryStock` mocking if it's a `final` class or has no no-arg accessible getters usable with plain `mock()` — check the entity's actual structure first (Task 2 already required reading it in full) and use whatever construction/mocking approach is consistent with this module's other tests.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -pl walmal-inventory -am test -Dtest=CategoryStockHealthServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — class/method don't exist yet.

- [ ] **Step 3: Create the interface and implementation**

```java
// CategoryStockHealthService.java
public interface CategoryStockHealthService {
    List<CategoryStockHealthDto> getStockHealthByCategory();
}
```

```java
// CategoryStockHealthServiceImpl.java
@Service
public class CategoryStockHealthServiceImpl implements CategoryStockHealthService {

    private final ProductCatalogService productCatalogService;
    private final InventoryStockRepository stockRepository;

    public CategoryStockHealthServiceImpl(ProductCatalogService productCatalogService, InventoryStockRepository stockRepository) {
        this.productCatalogService = productCatalogService;
        this.stockRepository = stockRepository;
    }

    @Override
    public List<CategoryStockHealthDto> getStockHealthByCategory() {
        List<CategoryProductVariantRow> rows = productCatalogService.getAllCategoryProductVariantMappings();

        Map<UUID, List<CategoryProductVariantRow>> byCategory = rows.stream()
                .collect(Collectors.groupingBy(CategoryProductVariantRow::categoryId));

        List<UUID> variantIds = rows.stream()
                .map(CategoryProductVariantRow::variantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, List<StockHealthStatus>> healthByVariant = stockRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.groupingBy(InventoryStock::getVariantId,
                        Collectors.mapping(InventoryStock::classifyHealth, Collectors.toList())));

        return byCategory.entrySet().stream()
                .map(entry -> {
                    List<CategoryProductVariantRow> categoryRows = entry.getValue();
                    String categoryName = categoryRows.get(0).categoryName();
                    long productCount = categoryRows.stream()
                            .map(CategoryProductVariantRow::productId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .count();
                    List<StockHealthStatus> statuses = categoryRows.stream()
                            .map(CategoryProductVariantRow::variantId)
                            .filter(Objects::nonNull)
                            .flatMap(vId -> healthByVariant.getOrDefault(vId, List.of()).stream())
                            .toList();
                    long ok = statuses.stream().filter(s -> s == StockHealthStatus.OK).count();
                    long low = statuses.stream().filter(s -> s == StockHealthStatus.LOW).count();
                    long critical = statuses.stream().filter(s -> s == StockHealthStatus.CRITICAL).count();
                    return new CategoryStockHealthDto(entry.getKey(), categoryName, productCount, ok, low, critical);
                })
                .sorted(Comparator.comparing(CategoryStockHealthDto::categoryName))
                .toList();
    }
}
```

Confirm `InventoryStock` actually exposes a `getVariantId()` getter (it should, per Lombok or manual getters — check the real file) and adjust if the accessor name differs.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -pl walmal-inventory -am test -Dtest=CategoryStockHealthServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add walmal-inventory/src/main/java/com/walmal/inventory/application/dto/CategoryStockHealthDto.java \
        walmal-inventory/src/main/java/com/walmal/inventory/application/CategoryStockHealthService.java \
        walmal-inventory/src/main/java/com/walmal/inventory/application/impl/CategoryStockHealthServiceImpl.java \
        walmal-inventory/src/test/java/com/walmal/inventory/application/impl/CategoryStockHealthServiceImplTest.java
git commit -m "feat(inventory): add category stock-health aggregation service"
```

---

### Task 4: Controller endpoint + WebMvcTest

**Files:**
- Create: `src/main/java/com/walmal/inventory/api/CategoryStockHealthController.java`
- Test: `src/test/java/com/walmal/inventory/api/CategoryStockHealthControllerTest.java`

- [ ] **Step 1: Write the failing test**

Follow this module's existing `@WebMvcTest` style (check `InventoryStockControllerTest.java` or similar for the exact `@Import`/security-test setup used in this module — it may differ slightly from `walmal-order`'s pattern used elsewhere in this session's earlier work; confirm before writing).

```java
@Test
void should_return200AndCategoryList_when_adminRequestsStockHealth() throws Exception {
    AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
    when(categoryStockHealthService.getStockHealthByCategory()).thenReturn(List.of(
            new CategoryStockHealthDto(UUID.randomUUID(), "Electronics", 5, 4, 1, 0)
    ));
    mockMvc.perform(get("/api/v1/inventory/categories/stock-health")
                    .with(authentication(buildAuth(admin))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].productCount").value(5));
}

@Test
void should_return403_when_customerRequestsStockHealth() throws Exception {
    AuthenticatedPrincipal customer = new AuthenticatedPrincipal(UUID.randomUUID(), "cust", "CUSTOMER");
    mockMvc.perform(get("/api/v1/inventory/categories/stock-health")
                    .with(authentication(buildAuth(customer))))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -pl walmal-inventory -am test -Dtest=CategoryStockHealthControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 404, controller doesn't exist yet.

- [ ] **Step 3: Create the controller**

```java
@RestController
@RequestMapping("/api/v1/inventory/categories")
public class CategoryStockHealthController {

    private final CategoryStockHealthService categoryStockHealthService;

    public CategoryStockHealthController(CategoryStockHealthService categoryStockHealthService) {
        this.categoryStockHealthService = categoryStockHealthService;
    }

    @GetMapping("/stock-health")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")
    public ApiResponse<List<CategoryStockHealthDto>> getStockHealthByCategory() {
        return ApiResponse.ok(categoryStockHealthService.getStockHealthByCategory());
    }
}
```

Add whatever `@Tag`/`@Operation` OpenAPI annotations this module's other controllers use, matching their style.

- [ ] **Step 4: Run to verify it passes, then the full module suite**

Run: `./mvnw -pl walmal-inventory -am test -Dtest=CategoryStockHealthControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (2 tests)

Run: `./mvnw -pl walmal-inventory -am test`
Expected: all pass, no regressions.

- [ ] **Step 5: Commit**

```bash
git add walmal-inventory/src/main/java/com/walmal/inventory/api/CategoryStockHealthController.java \
        walmal-inventory/src/test/java/com/walmal/inventory/api/CategoryStockHealthControllerTest.java
git commit -m "feat(inventory): add GET /inventory/categories/stock-health endpoint"
```

---

### Task 5: Integration tests (two single-module tests)

**Files:**
- Create: an integration test in `walmal-product` proving `getAllCategoryProductVariantMappings()`'s LEFT JOIN
- Create: an integration test in `walmal-inventory` proving `findByVariantIdIn(...)`

- [ ] **Step 1: Find each module's existing integration test pattern**

Read an existing `walmal-product` integration test (search `walmal-product/src/test/` for `@Tag("integration")` + Testcontainers) and `walmal-inventory`'s `InventoryIntegrationTest.java` (already used as a template in earlier session work) to match each module's exact setup style.

- [ ] **Step 2: Write the `walmal-product` integration test**

Seed: 1 category with 1 product with 1 variant (normal case), 1 category with 1 product with ZERO variants (proves LEFT JOIN doesn't drop it), 1 category with ZERO products (proves it still appears). Call `categoryRepository.findCategoryProductVariantRows()` (or via `ProductCatalogService` if that's easier to wire in this module's existing integration test style) and assert all 3 categories appear with correct null-handling.

- [ ] **Step 3: Write the `walmal-inventory` integration test**

Seed: a few `InventoryStock` rows with known `variantId`s and known `availableQuantity`/`lowStockThreshold` combinations spanning OK/LOW/CRITICAL. Call `inventoryStockRepository.findByVariantIdIn(...)` with a subset of the seeded variant IDs (proving the `IN` filter works, not just "returns everything"), assert the correct rows come back and irrelevant variant IDs are excluded.

- [ ] **Step 4: Run both**

Run: `./mvnw -pl walmal-product -am test -DexcludedGroups= -Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<the new test class>`
Run: `./mvnw -pl walmal-inventory -am test -DexcludedGroups= -Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<the new test class>`
Expected: both PASS. Docker must be running (verify with `docker ps` first).

- [ ] **Step 5: Commit**

```bash
git add walmal-product/src/test/java/com/walmal/product/... walmal-inventory/src/test/java/com/walmal/inventory/...
git commit -m "test: add integration tests for category-product-variant query and batch stock lookup"
```

(Fill in exact paths once Step 1's investigation determines them.)

---

### Task 6: Backend KB updates

**Files:**
- Modify: `docs/kb/architecture.md`
- Modify: `docs/kb/SYSTEM.md`

- [ ] **Step 1: Document the new endpoint and pattern in `architecture.md`**

Document `GET /api/v1/inventory/categories/stock-health` (auth, response shape, module ownership and WHY it lives in `walmal-inventory` not `walmal-product` — the one-directional Maven dependency — so a future engineer doesn't try to "fix" it by moving the endpoint). Note this as the second example of the "fetch flat, aggregate in Java" rollup pattern (the daily-summary endpoint was the first).

- [ ] **Step 2: Update `docs/kb/SYSTEM.md`**

New cross-repo contract entry: path, auth, response shape.

- [ ] **Step 3: Commit**

```bash
git add docs/kb/
git commit -m "docs(kb): document category stock-health endpoint and inventory-owns-it rationale"
```

---

## Frontend tasks (`walmal-admin`)

**Before starting:** confirm the backend endpoint from Tasks 1-6 is merged/available, and rebuild the `walmal` backend JAR (`cd walmal && ./mvnw -pl walmal-app -am -DskipTests clean package`) before any live/e2e verification — same stale-JAR gotcha as every previous backend change this session.

### Task 7: Dashboard chart pure logic (TDD)

**Files:**
- Create: `src/pages/dashboard/category-stock-health.ts`
- Test: `src/pages/dashboard/category-stock-health.test.ts`

- [ ] **Step 1: Write the failing test**

```typescript
import { describe, expect, it } from "vitest";
import { formatCategoryStockHealthForChart, type CategoryStockHealth } from "./category-stock-health";

describe("formatCategoryStockHealthForChart", () => {
  it("returns an empty array for empty input", () => {
    expect(formatCategoryStockHealthForChart([])).toEqual([]);
  });

  it("maps each category's counts into a chart-ready row", () => {
    const input: CategoryStockHealth[] = [
      { categoryId: "c1", categoryName: "Electronics", productCount: 5, okCount: 4, lowCount: 1, criticalCount: 0 },
    ];
    expect(formatCategoryStockHealthForChart(input)).toEqual([
      { categoryName: "Electronics", ok: 4, low: 1, critical: 0 },
    ]);
  });

  it("preserves input order (backend already sorts alphabetically)", () => {
    const input: CategoryStockHealth[] = [
      { categoryId: "c1", categoryName: "Zebra", productCount: 1, okCount: 1, lowCount: 0, criticalCount: 0 },
      { categoryId: "c2", categoryName: "Apple", productCount: 1, okCount: 1, lowCount: 0, criticalCount: 0 },
    ];
    expect(formatCategoryStockHealthForChart(input).map((r) => r.categoryName)).toEqual(["Zebra", "Apple"]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:unit -- category-stock-health`
Expected: FAIL — module not found

- [ ] **Step 3: Implement**

```typescript
// src/pages/dashboard/category-stock-health.ts
// Pure response-shaping for the dashboard's per-category stock-health stacked bar
// chart. No React, no fetching — kept separate so it's independently unit-testable
// (see category-stock-health.test.ts).

export interface CategoryStockHealth {
  categoryId: string;
  categoryName: string;
  productCount: number;
  okCount: number;
  lowCount: number;
  criticalCount: number;
}

export interface CategoryHealthChartRow {
  categoryName: string;
  ok: number;
  low: number;
  critical: number;
}

export function formatCategoryStockHealthForChart(categories: CategoryStockHealth[]): CategoryHealthChartRow[] {
  return categories.map((c) => ({
    categoryName: c.categoryName,
    ok: c.okCount,
    low: c.lowCount,
    critical: c.criticalCount,
  }));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test:unit -- category-stock-health`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/pages/dashboard/category-stock-health.ts src/pages/dashboard/category-stock-health.test.ts
git commit -m "feat: add pure formatting logic for category stock-health chart"
```

---

### Task 8: Dashboard — replace Stock Health chart

**Files:**
- Create: `src/pages/dashboard/CategoryStockHealthChart.tsx`
- Modify: `src/pages/dashboard/index.tsx`

Following the established convention (Phase 1's `NeedsAttention.tsx`/`QuickActions.tsx`, the daily-summary feature's `DailySummaryChart.tsx`) — build the new chart as its own extracted component from the start this time, not inline-then-refactor.

- [ ] **Step 1: Remove the old global stock-health logic from `index.tsx`**

Delete the `stockBarData` computation and its inputs (`criticalStock`, `lowStock`, `okStock` — but NOT `allStock`/`lowStockItems`, which still feed the "Low Stock Items" stat card and are unrelated to this chart). Delete the old `showStockHealthChart && (<Card>...</Card>)` JSX block (the single-series `<BarChart>` with per-`<Cell>` coloring).

- [ ] **Step 2: Add the fetch**

```typescript
interface CategoryStockHealth { categoryId: string; categoryName: string; productCount: number; okCount: number; lowCount: number; criticalCount: number; }

const categoryStockHealthResult = useAsyncData<CategoryStockHealth[]>(
  async () => {
    const { data: raw } = await apiClient.get(`${BASE}/inventory/categories/stock-health`);
    return unwrap<CategoryStockHealth[]>(raw);
  },
  showStockHealthChart,
);

const categoryHealthChartData = formatCategoryStockHealthForChart(categoryStockHealthResult.data ?? []);
```

Import `formatCategoryStockHealthForChart` from `./category-stock-health`. Reuse the existing `showStockHealthChart` permission flag — same gate as before (ADMIN + WAREHOUSE_MANAGER via `inventory/adjustment` create permission), unchanged.

- [ ] **Step 3: Build `CategoryStockHealthChart.tsx`**

```tsx
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { BarChart, Bar, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer } from "recharts";
import { Skeleton, WidgetError } from "./widgets";
import type { CategoryHealthChartRow } from "./category-stock-health";

interface Props {
  chartData: CategoryHealthChartRow[];
  loading: boolean;
  error: boolean;
  onRetry: () => void;
}

export function CategoryStockHealthChart({ chartData, loading, error, onRetry }: Props) {
  return (
    <Card>
      <CardHeader><CardTitle className="text-sm font-medium">Stock Health by Category</CardTitle></CardHeader>
      <CardContent>
        {loading ? (
          <Skeleton className="h-64 w-full" />
        ) : error ? (
          <WidgetError onRetry={onRetry} />
        ) : chartData.length === 0 ? (
          <div className="flex items-center justify-center h-64 text-sm text-muted-foreground">
            No category stock data available
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={256}>
            <BarChart data={chartData} layout="vertical" margin={{ top: 8, right: 24, left: 8, bottom: 8 }}>
              <XAxis type="number" allowDecimals={false} tick={{ fontSize: 12 }} />
              <YAxis type="category" dataKey="categoryName" tick={{ fontSize: 12 }} width={100} />
              <Tooltip />
              <Legend />
              <Bar dataKey="ok" stackId="health" name="OK" fill="#22c55e" />
              <Bar dataKey="low" stackId="health" name="Low" fill="#eab308" />
              <Bar dataKey="critical" stackId="health" name="Critical" fill="#ef4444" />
            </BarChart>
          </ResponsiveContainer>
        )}
      </CardContent>
    </Card>
  );
}
```

Colors match the existing OK/Low/Critical convention already used elsewhere on this dashboard (`stockBarData`'s old fill colors, and the pie chart's status colors).

- [ ] **Step 4: Wire it into `index.tsx`**

In the same card-row position the old Stock Health chart occupied (next to Orders-by-Status), gated on `showStockHealthChart`:

```tsx
{showStockHealthChart && (
  <CategoryStockHealthChart
    chartData={categoryHealthChartData}
    loading={categoryStockHealthResult.loading}
    error={categoryStockHealthResult.error}
    onRetry={categoryStockHealthResult.refetch}
  />
)}
```

Import `CategoryStockHealthChart` from `./CategoryStockHealthChart`. Remove now-unused recharts imports from `index.tsx` if `BarChart`/`Bar`/`Cell` are no longer used by anything else in that file (check — the Orders-by-Status pie chart doesn't use them, but confirm before removing).

- [ ] **Step 5: Type-check, lint, manual verification**

Run: `npx tsc -b --noEmit && npm run lint`
Expected: clean.

`npm run dev` with the live backend running (rebuild the JAR first per the note above), confirm the new stacked bar chart renders with real category data, hover a bar segment to confirm the tooltip shows OK/Low/Critical breakdown per category.

- [ ] **Step 6: Update KB**

`docs/kb/architecture.md` — document the replaced chart, its data source, and the new `CategoryStockHealthChart.tsx`/`category-stock-health.ts` files.

- [ ] **Step 7: Commit**

```bash
git add src/pages/dashboard/CategoryStockHealthChart.tsx src/pages/dashboard/index.tsx docs/kb/architecture.md
git commit -m "feat: replace global Stock Health chart with per-category breakdown"
```

---

### Task 9: Categories list pure logic (TDD)

**Files:**
- Modify: `src/pages/categories/list-helpers.ts`
- Modify: `src/pages/categories/list-helpers.test.ts`

- [ ] **Step 1: Write the failing test**

Add to the existing `list-helpers.test.ts` (don't create a new file — this repo's convention is one helpers file + one test file per page):

```typescript
describe("buildProductCountMap", () => {
  it("returns an empty map for empty input", () => {
    expect(buildProductCountMap([]).size).toBe(0);
  });

  it("maps categoryId to productCount", () => {
    const map = buildProductCountMap([
      { categoryId: "c1", categoryName: "Electronics", productCount: 5, okCount: 4, lowCount: 1, criticalCount: 0 },
      { categoryId: "c2", categoryName: "Grocery", productCount: 0, okCount: 0, lowCount: 0, criticalCount: 0 },
    ]);
    expect(map.get("c1")).toBe(5);
    expect(map.get("c2")).toBe(0);
    expect(map.get("nonexistent")).toBeUndefined();
  });
});
```

Add the import for `CategoryStockHealth` type — either duplicate the minimal shape needed here or import it from `../dashboard/category-stock-health` (prefer importing/reusing over duplicating, matching the precedent set when Task 7 of the daily-summary plan imported `DailySummaryDay` instead of redeclaring it).

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:unit -- list-helpers`
Expected: FAIL — `buildProductCountMap` not exported.

- [ ] **Step 3: Implement**

```typescript
// add to list-helpers.ts
import type { CategoryStockHealth } from "../dashboard/category-stock-health";

export function buildProductCountMap(categories: CategoryStockHealth[]): Map<string, number> {
  return new Map(categories.map((c) => [c.categoryId, c.productCount]));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test:unit -- list-helpers`
Expected: PASS (previous tests + 2 new)

- [ ] **Step 5: Commit**

```bash
git add src/pages/categories/list-helpers.ts src/pages/categories/list-helpers.test.ts
git commit -m "feat: add product-count map builder for categories list"
```

---

### Task 10: Categories list — wire the Products column

**Files:**
- Modify: `src/pages/categories/list.tsx`

- [ ] **Step 1: Add the fetch**

Follow the existing `useTable`/`useList` pattern already in this file (check how the Phase 1 brand filter or category dropdown fetches auxiliary data). Add a fetch to `GET /inventory/categories/stock-health`, build the count map via `buildProductCountMap`.

- [ ] **Step 2: Add the "Products" column**

Insert a new `ColumnDef` (position: your judgment — after "slug"/before "Status" reads naturally) rendering `productCountMap.get(row.original.categoryId ?? row.original.id) ?? "—"`. Confirm the exact category ID field name used elsewhere in this file (`categoryId` vs `id` — Phase 1's `getId()` helper in this file already handles this ambiguity, reuse it rather than re-deriving).

- [ ] **Step 3: Type-check, lint, manual verification**

Run: `npx tsc -b --noEmit && npm run lint`
Expected: clean.

`npm run dev` with the live backend running, confirm `/products/categories` shows a populated Products column matching what the dashboard chart's category counts show.

- [ ] **Step 4: Update KB**

`docs/kb/architecture.md` — Categories list now has a real product-count column (previously explicitly noted as Phase 2/deferred — update that note rather than just adding a new one).

- [ ] **Step 5: Commit**

```bash
git add src/pages/categories/list.tsx docs/kb/architecture.md
git commit -m "feat: add real product-count column to categories list"
```

---

### Task 11: E2E smoke tests + final KB

**Files:**
- Modify: `tests/e2e/dashboard.spec.ts`
- Modify: `tests/e2e/categories.spec.ts`
- Modify: `docs/kb/testing.md`

- [ ] **Step 1: Add a dashboard smoke test**

```typescript
test("Stock Health by Category chart renders", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Stock Health by Category")).toBeVisible();
});
```

- [ ] **Step 2: Add a categories smoke test**

```typescript
test("Products column shows a count", async ({ page }) => {
  await page.goto("/products/categories");
  await expect(page.getByRole("columnheader", { name: "Products" })).toBeVisible();
});
```

(Adjust the selector to match whatever the actual column header text/role turns out to be from Task 10.)

- [ ] **Step 3: Run the full e2e suite**

Run: `npm run test:e2e`
Expected: all pass (existing 15 + 2 new = 17). Backend JAR must be rebuilt from the Task 1-6 changes first (same gotcha as every prior backend change this session).

- [ ] **Step 4: Update KB**

`docs/kb/testing.md` — bump test count.

- [ ] **Step 5: Commit**

```bash
git add tests/e2e/dashboard.spec.ts tests/e2e/categories.spec.ts docs/kb/testing.md
git commit -m "test: add e2e smoke coverage for category stock-health chart and products column"
```

---

## Final verification checklist

- [ ] `walmal`: `./mvnw -pl walmal-product,walmal-inventory -am test` — all pass, no regressions
- [ ] `walmal`: both new integration tests pass (Docker required)
- [ ] `walmal-admin`: `npm run test:unit` — all pass (34 + ~5 new)
- [ ] `walmal-admin`: `npx tsc -b --noEmit`, `npm run build`, `npm run lint` — clean
- [ ] `walmal-admin`: `npm run test:e2e` — 17/17 pass, against a rebuilt backend JAR
- [ ] Both repos' KB docs updated in the same commits as the feature they document
