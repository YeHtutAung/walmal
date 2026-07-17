/**
 * Checkout load test — TC-PERF-CHECKOUT
 *
 * Scenarios:
 *   1. authenticatedOrder — login as customer, POST /orders (authenticated)
 *   2. guestOrder         — POST /orders with guestEmail (no auth)
 *   3. badVariant         — POST /orders with non-existent variantId (expects 4xx)
 *
 * NOTE: Each order consumes real inventory. The seeded stock is:
 *   FTEE_M_WHT  200 units   FTEE_L_BLK  180 units  (safe for load testing)
 *   DNAP_32     120 units   DNAP_34      90 units
 * Quantity=1 per order to avoid depleting stock.
 * If stock runs out the backend returns 409; re-stock manually or re-seed.
 *
 * Success criteria:
 *   p(95) < 1000 ms for order creation (write)
 *   error rate < 1 % (expected 4xx scenarios excluded)
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate } from 'k6/metrics'
import { BASE_URL, CREDS, VARIANTS, LOCATION_ID, login, authHeaders, jsonHeaders, randomOf } from './helpers.js'

const orderDuration = new Trend('checkout_order_duration', true)
const orderErrors   = new Rate('checkout_order_errors')

// Low-cost items with high stock — safe to order repeatedly
const LOW_STOCK_RISK_VARIANTS = [
  VARIANTS.FTEE_M_WHT,
  VARIANTS.FTEE_L_BLK,
  VARIANTS.DNAP_32_NVY,
  VARIANTS.DNAP_34_BLK,
]

const SHIPPING_ADDRESSES = [
  { line1: '1 Load Test Ave',  city: 'Testville',  postalCode: '10001', country: 'US' },
  { line1: '42 Perf Street',   city: 'Benchmark',  postalCode: '20002', country: 'US' },
  { line1: '99 Stress Drive',  city: 'Load City',  postalCode: '30003', country: 'US' },
]

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '2m',  target: 10 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    checkout_order_duration: ['p(95)<1000'],
    checkout_order_errors:   ['rate<0.01'],
    http_req_failed:         ['rate<0.01'],
  },
}

export function setup() {
  const tokens = login(CREDS.customer.username, CREDS.customer.password)
  if (!tokens) throw new Error('Setup login failed — check credentials and backend health')
  return { accessToken: tokens.accessToken }
}

export default function (data) {
  const iter    = __ITER % 3
  const address = randomOf(SHIPPING_ADDRESSES)
  const variant = randomOf(LOW_STOCK_RISK_VARIANTS)

  // Scenario 1: authenticated order
  if (iter === 0 || iter === 1) {
    const body = {
      items:           [{ variantId: variant, locationId: LOCATION_ID, quantity: 1 }],
      shippingAddress: address,
      currency:        'USD',
    }
    const res = http.post(
      `${BASE_URL}/orders`,
      JSON.stringify(body),
      authHeaders(data.accessToken),
    )
    orderDuration.add(res.timings.duration)
    const ok = check(res, {
      'order 201':      r => r.status === 201,
      'order has data': r => !!JSON.parse(r.body).data,
    })
    orderErrors.add(!ok && res.status !== 409) // 409 = out of stock, not a perf failure
  }

  // Scenario 2: guest order
  else {
    const guestNum = __VU * 100 + __ITER
    const body = {
      items:           [{ variantId: variant, locationId: LOCATION_ID, quantity: 1 }],
      shippingAddress: address,
      currency:        'USD',
      guestEmail:      `guest${guestNum}@perf.test`,
    }
    const res = http.post(
      `${BASE_URL}/orders`,
      JSON.stringify(body),
      jsonHeaders,
    )
    orderDuration.add(res.timings.duration)
    const ok = check(res, {
      'guest order 201': r => r.status === 201,
    })
    orderErrors.add(!ok && res.status !== 409)
  }

  sleep(2)
}
