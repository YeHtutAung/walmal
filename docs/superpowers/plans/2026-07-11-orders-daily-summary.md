# Orders Daily Summary (Time-Series Chart) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new admin-only aggregate endpoint (`GET /api/v1/orders/admin/daily-summary`, 30-day daily order-count + FULFILLED-revenue, zero-filled) to `walmal`, and a new dual-axis line chart on the `walmal-admin` dashboard that consumes it.

**Architecture:** Backend: a JPQL constructor-projection query fetches lightweight rows for the 30-day window; a new pure Java method in `OrderAdminService` buckets them by UTC date, zero-fills missing days, and sums FULFILLED revenue — no GROUP BY, matching the codebase's existing manual-aggregation convention (this is the first date-bucketing/aggregate pattern in the codebase; JPQL constructor-projection itself has prior art in `walmal-pos`'s `PosSaleRepository`). Frontend: a colocated pure function shapes the response for Recharts; a new `useAsyncData` fetch (following the file's existing pattern) feeds a new full-width dual-axis `LineChart` card.

**Tech Stack:** Backend — Spring Boot, Spring Data JPA, Spring Security `@PreAuthorize`, JUnit 5 + Mockito + Testcontainers (Postgres). Frontend — React, Refine, Recharts (new: `LineChart`, dual `<YAxis>` — first use of either in this codebase), Vitest.

**Spec:** `docs/superpowers/specs/2026-07-11-orders-daily-summary-design.md` (same repo) — read for full rationale; this plan implements it task-by-task.

---

## Repos and worktrees

This plan spans two sibling repos. **Tasks 1–5 execute against `walmal`; Tasks 6–8 execute against `walmal-admin`.** Set up two separate worktrees (one per repo) via the `using-git-worktrees` skill before starting each repo's task sequence — they are independent git repositories, not a monorepo, so this is two separate worktree setups, not one.

**Tasks 6–8 depend on Tasks 1–5 being complete** for the e2e smoke test (Task 8) to have a real endpoint to hit. The frontend unit tests (Task 6) have no such dependency and could technically run in parallel, but for simplicity execute sequentially: finish and merge the `walmal` side first, then start the `walmal-admin` side.

---

## File Structure

**New files (`walmal/walmal-order/`):**
- `src/main/java/com/walmal/order/application/dto/OrderTimeseriesRow.java` — lightweight JPQL projection record
- `src/main/java/com/walmal/order/application/dto/DailyOrderSummaryDto.java` — API response record
- `src/test/java/com/walmal/order/application/impl/OrderAdminServiceDailySummaryTest.java` — unit tests for the bucketing/zero-fill/sum logic
- `src/test/java/com/walmal/order/OrderDailySummaryIntegrationTest.java` — Testcontainers integration test

**Modified files (`walmal/walmal-order/`):**
- `src/main/java/com/walmal/order/infrastructure/OrderRepository.java` — new projection query
- `src/main/java/com/walmal/order/application/OrderAdminService.java` — new interface method
- `src/main/java/com/walmal/order/application/impl/OrderAdminServiceImpl.java` — new method implementation
- `src/main/java/com/walmal/order/api/OrderController.java` — new endpoint
- `src/test/java/com/walmal/order/api/OrderControllerTest.java` — new `@WebMvcTest` case

**KB (both repos):**
- `walmal`'s backend KB (wherever the module's architecture doc lives — check `docs/kb/` at implementation time, following this repo's existing structure)
- `walmal/docs/kb/SYSTEM.md` — new cross-repo contract entry
- `walmal-admin/docs/kb/architecture.md`

**New files (`walmal-admin/src/`):**
- `pages/dashboard/daily-summary.ts` — pure response-shaping/date-formatting function
- `pages/dashboard/daily-summary.test.ts`

**Modified files (`walmal-admin/`):**
- `src/pages/dashboard/index.tsx` — new fetch + new chart card
- `tests/e2e/dashboard.spec.ts` — new smoke test

---

## Backend tasks (`walmal`)

### Task 1: DTOs + pure aggregation logic (TDD)

**Files:**
- Create: `src/main/java/com/walmal/order/application/dto/OrderTimeseriesRow.java`
- Create: `src/main/java/com/walmal/order/application/dto/DailyOrderSummaryDto.java`
- Test: `src/test/java/com/walmal/order/application/impl/OrderAdminServiceDailySummaryTest.java`
- Modify: `src/main/java/com/walmal/order/application/OrderAdminService.java`
- Modify: `src/main/java/com/walmal/order/application/impl/OrderAdminServiceImpl.java`

- [ ] **Step 1: Create the two DTOs**

```java
// src/main/java/com/walmal/order/application/dto/OrderTimeseriesRow.java
package com.walmal.order.application.dto;

import com.walmal.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Lightweight JPQL projection — do not add fields beyond what the daily-summary query needs. */
public record OrderTimeseriesRow(Instant createdAt, BigDecimal totalAmount, String currency, OrderStatus status) {}
```

```java
// src/main/java/com/walmal/order/application/dto/DailyOrderSummaryDto.java
package com.walmal.order.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyOrderSummaryDto(LocalDate date, long orderCount, BigDecimal revenue, String currency) {}
```

- [ ] **Step 2: Write the failing test for the bucketing logic**

Add the new method signature to `OrderAdminService.java` first (interface), so the test compiles:

```java
// add to OrderAdminService.java, alongside listAllOrders/updateStatus
List<DailyOrderSummaryDto> buildDailySummary(List<OrderTimeseriesRow> rows, java.time.LocalDate endDateUtc);
```

`endDateUtc` is passed in (not computed via `LocalDate.now()` inside the method) specifically so this logic is deterministic and testable without mocking the clock.

```java
// src/test/java/com/walmal/order/application/impl/OrderAdminServiceDailySummaryTest.java
package com.walmal.order.application.impl;

import com.walmal.order.application.dto.DailyOrderSummaryDto;
import com.walmal.order.application.dto.OrderTimeseriesRow;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.infrastructure.OrderRepository;
// import this module's DomainEventPublisher and AuditService types (exact packages TBD at
// implementation time by checking OrderAdminServiceImpl's real import list)
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Follows this codebase's established service-impl unit test convention (confirmed via
// ProductManagementServiceImplTest, ProductCatalogServiceImplTest, NotificationServiceImplTest,
// AuthServiceImplTest, InventoryQueryServiceImplTest, PosSyncServiceImplTest): MockitoExtension +
// @Mock fields wired manually in @BeforeEach, not a hand-rolled `new ...(Mockito.mock(...), ...)`
// in a field initializer.
@ExtendWith(MockitoExtension.class)
class OrderAdminServiceDailySummaryTest {

    @Mock private OrderRepository orderRepository;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private AuditService auditService;

    private OrderAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderAdminServiceImpl(orderRepository, eventPublisher, auditService);
    }

    private static Instant onDate(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).plusHours(6).toInstant(); // mid-morning UTC
    }

    @Test
    void returns30ZeroFilledDays_whenNoOrders() {
        LocalDate end = LocalDate.of(2026, 7, 11);
        List<DailyOrderSummaryDto> result = service.buildDailySummary(List.of(), end);

        assertThat(result).hasSize(30);
        assertThat(result.get(0).date()).isEqualTo(end.minusDays(29));
        assertThat(result.get(29).date()).isEqualTo(end);
        assertThat(result).allMatch(d -> d.orderCount() == 0 && d.revenue().compareTo(BigDecimal.ZERO) == 0);
        assertThat(result.get(0).currency()).isEqualTo("USD");
    }

    @Test
    void countsAllStatuses_butOnlySumsFulfilledRevenue() {
        LocalDate day = LocalDate.of(2026, 7, 10);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(day), new BigDecimal("100.00"), "USD", OrderStatus.FULFILLED),
                new OrderTimeseriesRow(onDate(day), new BigDecimal("50.00"), "USD", OrderStatus.PENDING),
                new OrderTimeseriesRow(onDate(day), new BigDecimal("25.00"), "USD", OrderStatus.CANCELLED)
        );

        DailyOrderSummaryDto bucket = service.buildDailySummary(rows, day).stream()
                .filter(d -> d.date().equals(day)).findFirst().orElseThrow();

        assertThat(bucket.orderCount()).isEqualTo(3);
        assertThat(bucket.revenue()).isEqualByComparingTo("100.00");
    }

    @Test
    void groupsMultipleOrdersOnSameDay_andSeparatesDifferentDays() {
        LocalDate d1 = LocalDate.of(2026, 7, 9);
        LocalDate d2 = LocalDate.of(2026, 7, 10);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(d1), new BigDecimal("10.00"), "USD", OrderStatus.FULFILLED),
                new OrderTimeseriesRow(onDate(d1), new BigDecimal("20.00"), "USD", OrderStatus.FULFILLED),
                new OrderTimeseriesRow(onDate(d2), new BigDecimal("5.00"), "USD", OrderStatus.FULFILLED)
        );

        List<DailyOrderSummaryDto> result = service.buildDailySummary(rows, d2);
        DailyOrderSummaryDto bucket1 = result.stream().filter(d -> d.date().equals(d1)).findFirst().orElseThrow();
        DailyOrderSummaryDto bucket2 = result.stream().filter(d -> d.date().equals(d2)).findFirst().orElseThrow();

        assertThat(bucket1.orderCount()).isEqualTo(2);
        assertThat(bucket1.revenue()).isEqualByComparingTo("30.00");
        assertThat(bucket2.orderCount()).isEqualTo(1);
        assertThat(bucket2.revenue()).isEqualByComparingTo("5.00");
    }

    @Test
    void defaultsCurrencyToUsd_whenNoFulfilledOrdersInWindow() {
        LocalDate day = LocalDate.of(2026, 7, 10);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(day), new BigDecimal("50.00"), "EUR", OrderStatus.PENDING)
        );

        List<DailyOrderSummaryDto> result = service.buildDailySummary(rows, day);
        assertThat(result).allMatch(d -> d.currency().equals("USD"));
    }

    @Test
    void usesFirstFulfilledCurrencySeen_whenFulfilledOrdersExist() {
        LocalDate day = LocalDate.of(2026, 7, 10);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(day), new BigDecimal("50.00"), "EUR", OrderStatus.FULFILLED)
        );

        List<DailyOrderSummaryDto> result = service.buildDailySummary(rows, day);
        assertThat(result).allMatch(d -> d.currency().equals("EUR"));
    }
}
```

`OrderAdminServiceImpl`'s constructor is `(OrderRepository, DomainEventPublisher, AuditService)` — confirmed during plan review against the real file. Fill in the exact import paths for `DomainEventPublisher`/`AuditService` from `OrderAdminServiceImpl.java`'s own import list when implementing (both are unused by `buildDailySummary` itself, but the constructor requires them, so they're mocked like every other collaborator in this codebase's service tests).

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -pl walmal-order -am test -Dtest=OrderAdminServiceDailySummaryTest`
Expected: FAIL — `buildDailySummary` not implemented (compile error until Step 4).

- [ ] **Step 4: Implement `buildDailySummary` in `OrderAdminServiceImpl`**

```java
// add to OrderAdminServiceImpl.java
@Override
public List<DailyOrderSummaryDto> buildDailySummary(List<OrderTimeseriesRow> rows, LocalDate endDateUtc) {
    Map<LocalDate, List<OrderTimeseriesRow>> byDate = rows.stream()
            .collect(Collectors.groupingBy(r -> r.createdAt().atZone(ZoneOffset.UTC).toLocalDate()));

    String currency = rows.stream()
            .filter(r -> r.status() == OrderStatus.FULFILLED)
            .findFirst()
            .map(OrderTimeseriesRow::currency)
            .orElse("USD");

    List<DailyOrderSummaryDto> result = new ArrayList<>();
    for (LocalDate date = endDateUtc.minusDays(29); !date.isAfter(endDateUtc); date = date.plusDays(1)) {
        List<OrderTimeseriesRow> dayRows = byDate.getOrDefault(date, List.of());
        BigDecimal revenue = dayRows.stream()
                .filter(r -> r.status() == OrderStatus.FULFILLED)
                .map(OrderTimeseriesRow::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        result.add(new DailyOrderSummaryDto(date, dayRows.size(), revenue, currency));
    }
    return result;
}
```

Add the necessary imports (`java.time.LocalDate`, `java.time.ZoneOffset`, `java.util.stream.Collectors`, `java.util.ArrayList`, `java.util.Map`).

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl walmal-order -am test -Dtest=OrderAdminServiceDailySummaryTest`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add walmal-order/src/main/java/com/walmal/order/application/dto/OrderTimeseriesRow.java \
        walmal-order/src/main/java/com/walmal/order/application/dto/DailyOrderSummaryDto.java \
        walmal-order/src/main/java/com/walmal/order/application/OrderAdminService.java \
        walmal-order/src/main/java/com/walmal/order/application/impl/OrderAdminServiceImpl.java \
        walmal-order/src/test/java/com/walmal/order/application/impl/OrderAdminServiceDailySummaryTest.java
git commit -m "feat(order): add pure daily-summary bucketing logic with zero-fill"
```

---

### Task 2: Repository projection query

**Files:**
- Modify: `src/main/java/com/walmal/order/infrastructure/OrderRepository.java`
- Modify: `src/main/java/com/walmal/order/application/impl/OrderAdminServiceImpl.java`

- [ ] **Step 1: Add the projection query to `OrderRepository`**

Follow `walmal-pos`'s `PosSaleRepository` constructor-projection style (`SELECT new fully.qualified.Dto(...)`):

```java
// add to OrderRepository.java
@Query("SELECT new com.walmal.order.application.dto.OrderTimeseriesRow(" +
       "o.createdAt, o.totalAmount, o.currency, o.status) " +
       "FROM Order o WHERE o.createdAt >= :cutoff")
List<OrderTimeseriesRow> findForDailySummary(@Param("cutoff") java.time.Instant cutoff);
```

Add the `OrderTimeseriesRow` import.

- [ ] **Step 2: Add the public entry-point method to `OrderAdminServiceImpl`**

This is the method the controller will call — it computes the cutoff, fetches rows, and delegates to `buildDailySummary` from Task 1:

```java
// add to OrderAdminServiceImpl.java (and its interface method to OrderAdminService.java)
@Override
public List<DailyOrderSummaryDto> getDailySummary() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Instant cutoff = today.minusDays(29).atStartOfDay(ZoneOffset.UTC).toInstant();
    List<OrderTimeseriesRow> rows = orderRepository.findForDailySummary(cutoff);
    return buildDailySummary(rows, today);
}
```

Add to `OrderAdminService.java` interface: `List<DailyOrderSummaryDto> getDailySummary();`

- [ ] **Step 3: Verify compilation**

Run: `./mvnw -pl walmal-order -am compile`
Expected: BUILD SUCCESS. (No DB-backed test for the query itself yet — that's Task 4's job. This step is a compile-only check since `findForDailySummary` can't be meaningfully unit-tested without a real database.)

- [ ] **Step 4: Commit**

```bash
git add walmal-order/src/main/java/com/walmal/order/infrastructure/OrderRepository.java \
        walmal-order/src/main/java/com/walmal/order/application/OrderAdminService.java \
        walmal-order/src/main/java/com/walmal/order/application/impl/OrderAdminServiceImpl.java
git commit -m "feat(order): wire daily-summary repository query and service entry point"
```

---

### Task 3: Controller endpoint + WebMvcTest

**Files:**
- Modify: `src/main/java/com/walmal/order/api/OrderController.java`
- Modify: `src/test/java/com/walmal/order/api/OrderControllerTest.java`

- [ ] **Step 1: Write the failing WebMvcTest**

Follow the existing `/admin` test's exact style (`OrderControllerTest.java:157-171`):

```java
@Test
@DisplayName("should_return200AndSummaryList_when_adminRequestsDailySummary")
void should_return200AndSummaryList_when_adminRequestsDailySummary() throws Exception {
    AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
    when(orderAdminService.getDailySummary()).thenReturn(List.of(
            new DailyOrderSummaryDto(LocalDate.of(2026, 7, 11), 3, new BigDecimal("100.00"), "USD")
    ));
    mockMvc.perform(get("/api/v1/orders/admin/daily-summary")
                    .with(authentication(buildAuth(admin))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].orderCount").value(3));
}

@Test
@DisplayName("should_return403_when_customerRequestsDailySummary")
void should_return403_when_customerRequestsDailySummary() throws Exception {
    AuthenticatedPrincipal customer = new AuthenticatedPrincipal(UUID.randomUUID(), "cust", "CUSTOMER");
    mockMvc.perform(get("/api/v1/orders/admin/daily-summary")
                    .with(authentication(buildAuth(customer))))
            .andExpect(status().isForbidden());
}
```

Add the `DailyOrderSummaryDto`/`LocalDate` imports to the test file if not already present.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -pl walmal-order -am test -Dtest=OrderControllerTest`
Expected: FAIL — `404` (no such endpoint yet) on the first test.

- [ ] **Step 3: Add the controller endpoint**

Insert after the existing `/admin` (`listAllOrders`) method, following its exact style:

```java
@GetMapping("/admin/daily-summary")
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
@Operation(summary = "Daily order/revenue summary (admin)",
           description = "Returns a 30-day, zero-filled daily order count + FULFILLED revenue series. Admin and Staff only.")
public ApiResponse<List<DailyOrderSummaryDto>> getDailySummary() {
    return ApiResponse.ok(orderAdminService.getDailySummary());
}
```

Add the `DailyOrderSummaryDto` import.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -pl walmal-order -am test -Dtest=OrderControllerTest`
Expected: PASS (all `OrderControllerTest` cases, including the 2 new ones)

- [ ] **Step 5: Run the full module unit test suite**

Run: `./mvnw -pl walmal-order -am test`
Expected: PASS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add walmal-order/src/main/java/com/walmal/order/api/OrderController.java \
        walmal-order/src/test/java/com/walmal/order/api/OrderControllerTest.java
git commit -m "feat(order): add GET /orders/admin/daily-summary endpoint"
```

---

### Task 4: Integration test (Testcontainers)

**Files:**
- Create: `src/test/java/com/walmal/order/OrderDailySummaryIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

Follow `OrderIntegrationTest.java`'s existing `@SpringBootTest` + Testcontainers `PostgreSQLContainer` setup pattern (same package/module). Seed 2-3 orders with known `createdAt`/`status`/`totalAmount` (one `FULFILLED`, one `PENDING`, spread across 2 distinct days within the 30-day window), call the real repository → service pipeline (either via `OrderAdminService.getDailySummary()` directly, or through `MockMvc` against the real controller — follow whichever style `OrderIntegrationTest.java` already uses), and assert:
- Result has exactly 30 entries
- The two seeded days have correct `orderCount`/`revenue`
- All other 28 days are zero-filled (`orderCount == 0`, `revenue == 0`)

This is the one test in this plan that actually proves the JPQL constructor-projection query executes correctly against real Postgres — write it carefully, don't just assert on shape.

- [ ] **Step 2: Run it**

Run: `./mvnw -pl walmal-order -am test -DexcludedGroups= -Dapi.version=1.44 -Dtest=OrderDailySummaryIntegrationTest`
Expected: PASS. (Requires Docker running for Testcontainers — confirm Docker is available before running; if it isn't, this is a genuine BLOCKED condition, not something to work around.)

- [ ] **Step 3: Commit**

```bash
git add walmal-order/src/test/java/com/walmal/order/OrderDailySummaryIntegrationTest.java
git commit -m "test(order): add integration test proving daily-summary query against real Postgres"
```

---

### Task 5: Backend KB updates (including cross-repo contract)

**Files:**
- Modify: `docs/kb/architecture.md` (this repo's KB is a flat, 5-file structure — `architecture.md`, `conventions.md`, `gotchas.md`, `testing.md`, `SYSTEM.md` — no per-module split; `architecture.md` already documents the module table and Flyway migration map, so it's the correct target)
- Modify: `docs/kb/SYSTEM.md`

- [ ] **Step 1: Document the new endpoint and the aggregation precedent in `docs/kb/architecture.md`**

Add an entry describing `GET /api/v1/orders/admin/daily-summary` (auth: ADMIN/STAFF, response shape, 30-day zero-filled). Explicitly document the **aggregation approach** (JPQL projection + Java-side date-bucketing) as this codebase's first GROUP BY-equivalent pattern, so a future similar feature has precedent to follow — but don't overstate it as the first JPQL projection query (note `walmal-pos`'s `PosSaleRepository` as prior art for that specific technique).

- [ ] **Step 2: Update `docs/kb/SYSTEM.md`**

This is a new cross-repo contract (a new endpoint `walmal-admin` will consume) — add it per the maintenance rule: endpoint path, auth requirement, request/response shape.

- [ ] **Step 3: Commit**

```bash
git add docs/kb/
git commit -m "docs(kb): document orders daily-summary endpoint and aggregation precedent"
```

---

## Frontend tasks (`walmal-admin`)

**Before starting:** confirm the backend endpoint from Tasks 1-5 is merged/available (at minimum, the code should be merged to `walmal`'s main branch; for e2e testing in Task 8 the backend JAR needs to be rebuilt from that merged code — see Task 8's notes).

### Task 6: `daily-summary.ts` pure logic (TDD)

**Files:**
- Create: `src/pages/dashboard/daily-summary.ts`
- Test: `src/pages/dashboard/daily-summary.test.ts`

- [ ] **Step 1: Write the failing test**

Follow `attention.ts`'s module header/export style exactly (see `src/pages/dashboard/attention.ts` for the template).

```typescript
// src/pages/dashboard/daily-summary.test.ts
import { describe, expect, it } from "vitest";
import { formatDailySummaryForChart, type DailySummaryDay } from "./daily-summary";

describe("formatDailySummaryForChart", () => {
  it("returns an empty array for empty input", () => {
    expect(formatDailySummaryForChart([])).toEqual([]);
  });

  it("formats each day's date as a short label and passes through count/revenue", () => {
    const input: DailySummaryDay[] = [
      { date: "2026-07-11", orderCount: 9, revenue: 2199.98, currency: "USD" },
    ];
    expect(formatDailySummaryForChart(input)).toEqual([
      { date: "2026-07-11", label: "Jul 11", orderCount: 9, revenue: 2199.98 },
    ]);
  });

  it("preserves day order (does not re-sort)", () => {
    const input: DailySummaryDay[] = [
      { date: "2026-07-10", orderCount: 1, revenue: 10, currency: "USD" },
      { date: "2026-07-11", orderCount: 2, revenue: 20, currency: "USD" },
    ];
    const result = formatDailySummaryForChart(input);
    expect(result.map((d) => d.date)).toEqual(["2026-07-10", "2026-07-11"]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:unit -- daily-summary`
Expected: FAIL — module not found

- [ ] **Step 3: Implement**

```typescript
// src/pages/dashboard/daily-summary.ts
// Pure date-label formatting + response-shaping for the dashboard's daily
// orders/revenue chart. No React, no fetching — kept separate so it's
// independently unit-testable (see daily-summary.test.ts).

export interface DailySummaryDay {
  date: string; // "YYYY-MM-DD"
  orderCount: number;
  revenue: number;
  currency: string;
}

export interface ChartPoint {
  date: string;
  label: string;
  orderCount: number;
  revenue: number;
}

export function formatDailySummaryForChart(days: DailySummaryDay[]): ChartPoint[] {
  return days.map((d) => ({
    date: d.date,
    label: new Date(`${d.date}T00:00:00Z`).toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      timeZone: "UTC",
    }),
    orderCount: d.orderCount,
    revenue: d.revenue,
  }));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test:unit -- daily-summary`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/pages/dashboard/daily-summary.ts src/pages/dashboard/daily-summary.test.ts
git commit -m "feat: add pure formatting logic for daily orders/revenue chart"
```

---

### Task 7: Wire fetch + new chart card into `index.tsx`

**Files:**
- Modify: `src/pages/dashboard/index.tsx`

- [ ] **Step 1: Add the `LineChart`/`Line` imports**

`index.tsx`'s recharts import block (currently lines 12-23) needs `LineChart, Line` added — this is the first use of either in the codebase, so there's no existing example to copy in this file; refer to Recharts' standard dual-axis API (`<YAxis yAxisId="...">` on each `<Line>`/`<YAxis>` pair).

- [ ] **Step 2: Add the fetch**

Follow the exact `terminalsResult` template (`index.tsx`, current `terminalsResult` block) — a new interface + `useAsyncData` call:

```typescript
interface DailySummaryDay { date: string; orderCount: number; revenue: number; currency: string; }

const dailySummaryResult = useAsyncData<DailySummaryDay[]>(
  async () => {
    const { data: raw } = await apiClient.get(`${BASE}/orders/admin/daily-summary`);
    return unwrap<DailySummaryDay[]>(raw);
  },
  showOrders,
);

const chartData = formatDailySummaryForChart(dailySummaryResult.data ?? []);
```

Add `import { formatDailySummaryForChart } from "./daily-summary";`.

- [ ] **Step 3: Add the new chart card**

Insert a new full-width `<Card>` in its own row (a new `<div className="...">` wrapper, not inside the existing two-chart grid row), placed between the existing two-chart row and the "Recent Activity" section. Gate on `showOrders` (matches what feeds it). Follow the existing chart cards' `CardHeader`/`CardTitle`/loading-skeleton/error pattern:

```tsx
{showOrders && (
  <Card>
    <CardHeader><CardTitle className="text-sm font-medium">Orders &amp; Revenue (Last 30 Days)</CardTitle></CardHeader>
    <CardContent>
      {dailySummaryResult.loading ? (
        <Skeleton className="h-64 w-full" />
      ) : dailySummaryResult.error ? (
        <WidgetError onRetry={dailySummaryResult.refetch} />
      ) : (
        <ResponsiveContainer width="100%" height={256}>
          <LineChart data={chartData}>
            <XAxis dataKey="label" tick={{ fontSize: 12 }} />
            <YAxis yAxisId="orders" tick={{ fontSize: 12 }} allowDecimals={false} />
            <YAxis yAxisId="revenue" orientation="right" tick={{ fontSize: 12 }} />
            <Tooltip />
            <Legend />
            <Line yAxisId="orders" type="monotone" dataKey="orderCount" name="Orders" stroke="#3b82f6" dot={false} />
            <Line yAxisId="revenue" type="monotone" dataKey="revenue" name="Revenue" stroke="#22c55e" dot={false} />
          </LineChart>
        </ResponsiveContainer>
      )}
    </CardContent>
  </Card>
)}
```

Import `Skeleton`/`WidgetError` from `./widgets` if not already imported in this file (they should be, from Phase 1 — confirm).

- [ ] **Step 4: Type-check and lint**

Run: `npx tsc -b --noEmit && npm run lint`
Expected: clean, no new errors beyond the documented pre-existing baseline.

- [ ] **Step 5: Manual verification**

`npm run dev`, confirm the new chart card renders (with the real backend from Tasks 1-5 running — this is the point where the frontend needs the backend actually available, unlike Phase 1's mocked/best-effort checks), hover a data point to confirm both series show in the tooltip.

- [ ] **Step 6: Update KB**

`docs/kb/architecture.md` — document the new chart, its data source, and the `daily-summary.ts` pure-logic file.

- [ ] **Step 7: Commit**

```bash
git add src/pages/dashboard/index.tsx docs/kb/architecture.md
git commit -m "feat: add daily orders/revenue chart to dashboard"
```

---

### Task 8: E2E smoke test

**Files:**
- Modify: `tests/e2e/dashboard.spec.ts`

- [ ] **Step 1: Add the smoke test**

```typescript
test("Orders & Revenue chart renders", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Orders & Revenue (Last 30 Days)")).toBeVisible();
});
```

Do not assert on specific chart values — seeded E2E test data doesn't span 30 days, so only assert the widget renders without error (matches this repo's Phase 1 e2e philosophy).

- [ ] **Step 2: Run the full e2e suite**

Run: `npm run test:e2e`
Expected: all pass (existing 14 + 1 new = 15). Requires the `walmal` backend JAR rebuilt from the Task 1-5 changes (`cd ../walmal && ./mvnw -pl walmal-app -am -DskipTests clean package`) — the stale-JAR gotcha from Phase 1 applies here since this is a real backend code change, not just frontend.

- [ ] **Step 3: Update KB**

`docs/kb/testing.md` — bump test count.

- [ ] **Step 4: Commit**

```bash
git add tests/e2e/dashboard.spec.ts docs/kb/testing.md
git commit -m "test: add e2e smoke coverage for daily orders/revenue chart"
```

---

## Final verification checklist

- [ ] `walmal`: `./mvnw -pl walmal-order -am test` — all pass, no regressions
- [ ] `walmal`: `./mvnw -pl walmal-order -am test -DexcludedGroups= -Dapi.version=1.44` — integration test passes (Docker required)
- [ ] `walmal-admin`: `npm run test:unit` — all pass (Phase 1's 31 + 3 new = 34)
- [ ] `walmal-admin`: `npx tsc -b --noEmit`, `npm run build`, `npm run lint` — clean
- [ ] `walmal-admin`: `npm run test:e2e` — 15/15 pass, against a rebuilt backend JAR
- [ ] Both repos' KB docs (`walmal`'s module KB + `SYSTEM.md`; `walmal-admin`'s `architecture.md` + `testing.md`) updated in the same commits as the feature they document
