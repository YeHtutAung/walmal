# Walmal Backend — k6 Performance Tests

Load tests for the Walmal Spring Boot monolith using [k6](https://k6.io).

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| k6   | ≥ 0.48  | `--report-file` flag for HTML output requires 0.48+ |
| Docker Compose | any | Starts postgres, redis, rabbitmq, minio, mailhog |
| Spring Boot JAR | current | `walmal-app/target/walmal-app-0.1.0-SNAPSHOT.jar` |

Install k6: https://k6.io/docs/get-started/installation/
(Windows: `winget install k6 --source winget`)

## Quick Start

```bash
# 1. Start infrastructure
docker compose -f docker-compose.yml up -d postgres redis rabbitmq minio mailhog

# 2. Start backend (from repo root)
java -jar walmal-app/target/walmal-app-0.1.0-SNAPSHOT.jar

# 3. Run all tests (from repo root)
./tests/performance/run.sh

# 4. Run a single test
k6 run --env BASE_URL=http://localhost:8080/api/v1 tests/performance/auth.load.js
```

## Scripts

| File | Role | Scenarios |
|------|------|-----------|
| `auth.load.js` | — | Login, token refresh, invalid credentials (expect 401) |
| `product.load.js` | — | Search, categories, product detail, variants (all public) |
| `checkout.load.js` | CUSTOMER | Authenticated order, guest order |
| `pos.load.js` | POS_OPERATOR | Online sale, idempotent retry, list sales, list terminals |
| `inventory.load.js` | WAREHOUSE_MANAGER | Stock reads, availability check, stock adjust (+1), transfer |
| `warehouse.load.js` | WAREHOUSE_STAFF + ADMIN | Task list, task detail, fulfillment read, location list |

## Load Profile

All scripts use the same ramp pattern unless noted:

```
0 ──30s──▶ peak VUs ──2m──▶ peak VUs ──30s──▶ 0
```

| Script | Peak VUs | Rationale |
|--------|----------|-----------|
| auth | 20 | Auth is a hot path; tokens cached per VU |
| product | 30 | Highest read traffic, fully public |
| checkout | 10 | Write-heavy; orders consume inventory |
| pos | 15 | Moderate write; idempotency key required |
| inventory | 20 | Mix of reads and +1 adjustments (stock-safe) |
| warehouse | 15 | Read-only sustained load |

## Success Criteria (Thresholds)

| Metric | Threshold |
|--------|-----------|
| Read p(95) | < 500 ms |
| Write / checkout p(95) | < 1000 ms |
| Error rate | < 1 % |

k6 exits non-zero when any threshold is breached, making CI integration straightforward.

## Output

```
tests/performance/results/
├── auth.json        ← k6 JSON summary
├── auth.html        ← HTML report (open in browser)
├── product.json
├── product.html
└── ...
```

Open `results/<name>.html` in any browser to view charts, percentiles, and check results.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080/api/v1` | Backend API base URL |

Override for staging:
```bash
BASE_URL=http://staging.walmal.internal:8080/api/v1 ./tests/performance/run.sh
```

## Seeded Test Data

The scripts rely on stable UUIDs from the dev seed migrations:

| Resource | UUID | Notes |
|----------|------|-------|
| Main Warehouse location | `a0000000-...-0001` | V4 migration |
| Buffer location | `a0000000-...-0002` | V4 migration |
| POS Terminal | `b0000000-...-0001` | V6 migration |
| Galaxy S24 Ultra 256GB variant | `20000000-...-0001` | V9 migration, 50 stock |
| Classic Tee M White variant | `20000000-...-0006` | V9 migration, 200 stock |

The checkout and POS tests order only high-stock items (T-shirts, jeans) to avoid depleting
inventory during a test run. If you see 409 responses, re-seed with:
```sql
UPDATE inventory_stock SET available_quantity = 200 WHERE variant_id = '20000000-0000-0000-0000-000000000006';
```

## Credentials

Seeded by V11 and V12 migrations:

| Username | Password | Role |
|----------|----------|------|
| `customer_test` | `TestPass123!` | CUSTOMER |
| `admin_test` | `AdminPass123!` | ADMIN |
| `warehouse_manager` | `wm123456` | WAREHOUSE_MANAGER |
| `warehouse_staff` | `ws123456` | WAREHOUSE_STAFF |
| `pos_operator` | `pos123456` | POS_OPERATOR |

## Rate Limiting

The backend rate-limits unauthenticated requests to 20 req/min by default.
Start the JAR with a higher limit for load testing:

```bash
java -Dwalmal.rate-limit.unauthenticated-limit=1000 \
     -jar walmal-app/target/walmal-app-0.1.0-SNAPSHOT.jar
```

## Interpreting Results

Key metrics to review after each run:

- **`http_req_duration{p(95)}`** — 95th percentile latency. Should be < 500 ms for reads, < 1000 ms for writes.
- **`http_req_failed`** — Rate of failed requests (non-2xx or network error). Target < 1 %.
- **`iterations`** — Total VU iterations completed. Higher is better.
- **Custom trends** (e.g., `auth_login_duration`) — Per-scenario latency breakdown.

A threshold breach appears in the summary as `✗` and causes a non-zero exit code:
```
✗ auth_login_duration.........: p(95)=634ms  threshold: p(95)<500ms FAILED
```

## CI Integration

```yaml
# .github/workflows/perf.yml (example)
- name: Run performance tests
  run: |
    BASE_URL=${{ vars.STAGING_URL }}/api/v1 ./tests/performance/run.sh auth product
  env:
    BASE_URL: ${{ vars.STAGING_URL }}/api/v1
```
