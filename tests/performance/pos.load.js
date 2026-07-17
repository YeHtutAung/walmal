/**
 * POS load test — TC-PERF-POS
 *
 * Scenarios:
 *   1. onlineSale      — POST /pos/sales with X-Idempotency-Key (POS_OPERATOR)
 *   2. idempotentRetry — same sale body + same key sent twice (both should return 201 with same data)
 *   3. listSales       — GET /pos/sales?terminalId= (read)
 *   4. getSale         — GET /pos/sales/{saleId} (read, using a sale created in setup)
 *   5. listTerminals   — GET /pos/terminals (read)
 *
 * Seeded terminal: b0000000-0000-0000-0000-000000000001 (Main Store Terminal 1, ACTIVE)
 *
 * Success criteria:
 *   p(95) < 1000 ms for sale creation
 *   p(95) < 500  ms for reads
 *   error rate < 1 %
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate } from 'k6/metrics'
import { BASE_URL, CREDS, VARIANTS, LOCATION_ID, TERMINAL_ID, login, authHeaders, randomOf } from './helpers.js'

const saleDuration     = new Trend('pos_sale_duration', true)
const readDuration     = new Trend('pos_read_duration', true)
const saleErrors       = new Rate('pos_sale_errors')

// Items safe for repeated POS sales (high stock)
const POS_VARIANTS = [
  VARIANTS.FTEE_M_WHT,
  VARIANTS.FTEE_L_BLK,
  VARIANTS.DNAP_32_NVY,
  VARIANTS.DNAP_34_BLK,
]

export const options = {
  stages: [
    { duration: '30s', target: 15 },
    { duration: '2m',  target: 15 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    pos_sale_duration:  ['p(95)<1000'],
    pos_read_duration:  ['p(95)<500'],
    pos_sale_errors:    ['rate<0.01'],
    http_req_failed:    ['rate<0.01'],
  },
}

export function setup() {
  const tokens = login(CREDS.posOperator.username, CREDS.posOperator.password)
  if (!tokens) throw new Error('POS operator login failed')

  // Create one seed sale to use in getSale scenario
  const idempotencyKey = `setup-${Date.now()}`
  const body = {
    terminalId: TERMINAL_ID,
    items:      [{ variantId: VARIANTS.FTEE_M_WHT, locationId: LOCATION_ID, quantity: 1 }],
    currency:   'USD',
  }
  const res = http.post(
    `${BASE_URL}/pos/sales`,
    JSON.stringify(body),
    {
      headers: {
        'Content-Type':    'application/json',
        Authorization:     `Bearer ${tokens.accessToken}`,
        'X-Idempotency-Key': idempotencyKey,
      },
    },
  )
  check(res, { 'setup sale 201': r => r.status === 201 })
  const saleId = (res.status === 201) ? JSON.parse(res.body).data.id : null
  return { accessToken: tokens.accessToken, seedSaleId: saleId }
}

export default function (data) {
  const iter    = __ITER % 5
  const variant = randomOf(POS_VARIANTS)

  // Scenario 1 & 2: online sale (unique idempotency key per VU+iter)
  if (iter === 0 || iter === 1) {
    const idempotencyKey = `vu${__VU}-iter${__ITER}-${Date.now()}`
    const body = {
      terminalId: TERMINAL_ID,
      items:      [{ variantId: variant, locationId: LOCATION_ID, quantity: 1 }],
      currency:   'USD',
    }
    const params = {
      headers: {
        'Content-Type':      'application/json',
        Authorization:       `Bearer ${data.accessToken}`,
        'X-Idempotency-Key': idempotencyKey,
      },
    }

    // First attempt
    const res1 = http.post(`${BASE_URL}/pos/sales`, JSON.stringify(body), params)
    saleDuration.add(res1.timings.duration)
    const ok1 = check(res1, {
      'sale 201':      r => r.status === 201,
      'sale has data': r => !!JSON.parse(r.body).data,
    })
    saleErrors.add(!ok1 && res1.status !== 409)

    if (iter === 1 && ok1) {
      // Idempotent retry with same key — must also succeed
      const res2 = http.post(`${BASE_URL}/pos/sales`, JSON.stringify(body), params)
      check(res2, { 'idempotent retry 201': r => r.status === 201 })
    }
  }

  // Scenario 3: list sales by terminal (read)
  else if (iter === 2) {
    const res = http.get(
      `${BASE_URL}/pos/sales?terminalId=${TERMINAL_ID}&page=0&size=20`,
      authHeaders(data.accessToken),
    )
    readDuration.add(res.timings.duration)
    check(res, { 'list sales 200': r => r.status === 200 })
  }

  // Scenario 4: get specific sale (read)
  else if (iter === 3 && data.seedSaleId) {
    const res = http.get(
      `${BASE_URL}/pos/sales/${data.seedSaleId}`,
      authHeaders(data.accessToken),
    )
    readDuration.add(res.timings.duration)
    check(res, { 'get sale 200': r => r.status === 200 })
  }

  // Scenario 5: list terminals (read)
  else {
    const res = http.get(`${BASE_URL}/pos/terminals`, authHeaders(data.accessToken))
    readDuration.add(res.timings.duration)
    check(res, { 'list terminals 200': r => r.status === 200 })
  }

  sleep(1)
}
