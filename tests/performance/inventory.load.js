/**
 * Inventory load test — TC-PERF-INVENTORY
 *
 * Scenarios:
 *   Reads  (WAREHOUSE_MANAGER):
 *     1. listStock       — GET /inventory/stock (paginated)
 *     2. stockDetail     — GET /inventory/stock/{variantId}/{locationId}
 *     3. availability    — GET /inventory/stock/{variantId}/availability
 *     4. stockCheck      — GET /inventory/stock/{variantId}/check?quantity=N
 *     5. movements       — GET /inventory/movements/variant/{variantId}
 *     6. defaultLocation — GET /inventory/locations/default (public)
 *
 *   Writes (WAREHOUSE_MANAGER):
 *     7. adjust          — POST /inventory/stock/adjust (delta=+1 to keep stock growing, not depleting)
 *     8. transfer        — POST /inventory/stock/transfer (Main Warehouse ↔ Buffer)
 *
 * Success criteria:
 *   p(95) < 500 ms for reads
 *   p(95) < 1000 ms for writes
 *   error rate < 1 %
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate } from 'k6/metrics'
import { BASE_URL, CREDS, VARIANTS, LOCATION_ID, BUFFER_LOC_ID, login, authHeaders, randomOf } from './helpers.js'

const readDuration  = new Trend('inventory_read_duration', true)
const writeDuration = new Trend('inventory_write_duration', true)
const writeErrors   = new Rate('inventory_write_errors')

const VARIANT_IDS = Object.values(VARIANTS)

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '2m',  target: 20 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    inventory_read_duration:  ['p(95)<500'],
    inventory_write_duration: ['p(95)<1000'],
    inventory_write_errors:   ['rate<0.01'],
    http_req_failed:          ['rate<0.01'],
  },
}

export function setup() {
  const tokens = login(CREDS.warehouseManager.username, CREDS.warehouseManager.password)
  if (!tokens) throw new Error('Warehouse manager login failed')
  return { accessToken: tokens.accessToken }
}

export default function (data) {
  const iter      = __ITER % 8
  const variantId = randomOf(VARIANT_IDS)
  const headers   = authHeaders(data.accessToken)

  switch (iter) {
    // ── Read scenarios ────────────────────────────────────────────────────────
    case 0: {
      const page = Math.floor(Math.random() * 3)
      const res  = http.get(`${BASE_URL}/inventory/stock?page=${page}&size=20`, headers)
      readDuration.add(res.timings.duration)
      check(res, { 'list stock 200': r => r.status === 200 })
      break
    }
    case 1: {
      const res = http.get(`${BASE_URL}/inventory/stock/${variantId}/${LOCATION_ID}`, headers)
      readDuration.add(res.timings.duration)
      check(res, { 'stock detail 200': r => r.status === 200 })
      break
    }
    case 2: {
      const res = http.get(`${BASE_URL}/inventory/stock/${variantId}/availability`, headers)
      readDuration.add(res.timings.duration)
      check(res, { 'availability 200': r => r.status === 200 })
      break
    }
    case 3: {
      const qty = Math.ceil(Math.random() * 5)
      const res = http.get(`${BASE_URL}/inventory/stock/${variantId}/check?quantity=${qty}`, headers)
      readDuration.add(res.timings.duration)
      check(res, { 'stock check 200': r => r.status === 200 })
      break
    }
    case 4: {
      const res = http.get(`${BASE_URL}/inventory/movements/variant/${variantId}`, headers)
      readDuration.add(res.timings.duration)
      check(res, { 'movements 200': r => r.status === 200 })
      break
    }
    case 5: {
      // Public endpoint — no auth required
      const res = http.get(`${BASE_URL}/inventory/locations/default`)
      readDuration.add(res.timings.duration)
      check(res, { 'default location 200': r => r.status === 200 })
      break
    }

    // ── Write scenarios ───────────────────────────────────────────────────────
    case 6: {
      // Adjust +1 to avoid draining stock during load testing
      const body = {
        variantId:  variantId,
        locationId: LOCATION_ID,
        delta:      1,
        reason:     'k6-perf-test-adjust',
      }
      const res = http.post(`${BASE_URL}/inventory/stock/adjust`, JSON.stringify(body), headers)
      writeDuration.add(res.timings.duration)
      const ok = check(res, { 'adjust 204': r => r.status === 204 })
      writeErrors.add(!ok)
      break
    }
    case 7: {
      // Transfer 1 unit from Main Warehouse to Buffer
      const body = {
        variantId:      VARIANTS.S24_256_BLK, // always has stock seeded in both locations
        fromLocationId: LOCATION_ID,
        toLocationId:   BUFFER_LOC_ID,
        quantity:       1,
      }
      const res = http.post(`${BASE_URL}/inventory/stock/transfer`, JSON.stringify(body), headers)
      writeDuration.add(res.timings.duration)
      // 409 = insufficient stock in source — not a perf failure, just track it
      const ok = check(res, { 'transfer 204 or 409': r => r.status === 204 || r.status === 409 })
      writeErrors.add(!ok)
      break
    }
    default:
      break
  }

  sleep(1)
}
