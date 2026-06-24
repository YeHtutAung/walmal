/**
 * Product catalog load test — TC-PERF-PRODUCT
 *
 * Endpoints (all public, no auth):
 *   GET /product/search?q=&page=&size=
 *   GET /product/categories
 *   GET /product/{productId}
 *   GET /product/{productId}/variants
 *
 * Success criteria:
 *   p(95) < 500 ms for all reads
 *   error rate < 1 %
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend } from 'k6/metrics'
import { BASE_URL, PRODUCTS, randomOf } from './helpers.js'

const searchDuration   = new Trend('product_search_duration', true)
const categoryDuration = new Trend('product_category_duration', true)
const detailDuration   = new Trend('product_detail_duration', true)

const SEARCH_TERMS = ['galaxy', 'iphone', 'macbook', 'tee', 'jeans', 'samsung', 'apple', '']
const PRODUCT_IDS  = Object.values(PRODUCTS)

export const options = {
  stages: [
    { duration: '30s', target: 30 },
    { duration: '2m',  target: 30 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    product_search_duration:   ['p(95)<500'],
    product_category_duration: ['p(95)<500'],
    product_detail_duration:   ['p(95)<500'],
    http_req_failed:           ['rate<0.01'],
  },
}

export default function () {
  const iter = __ITER % 4

  // Scenario 1: search with random term
  if (iter === 0) {
    const q    = randomOf(SEARCH_TERMS)
    const page = Math.floor(Math.random() * 3)
    const res  = http.get(`${BASE_URL}/product/search?q=${encodeURIComponent(q)}&page=${page}&size=20`)
    searchDuration.add(res.timings.duration)
    check(res, {
      'search 200':          r => r.status === 200,
      'search has content':  r => !!JSON.parse(r.body).data,
    })
  }

  // Scenario 2: list categories
  else if (iter === 1) {
    const res = http.get(`${BASE_URL}/product/categories`)
    categoryDuration.add(res.timings.duration)
    check(res, {
      'categories 200':        r => r.status === 200,
      'categories is array':   r => Array.isArray(JSON.parse(r.body).data),
    })
  }

  // Scenario 3: product detail
  else if (iter === 2) {
    const productId = randomOf(PRODUCT_IDS)
    const res = http.get(`${BASE_URL}/product/${productId}`)
    detailDuration.add(res.timings.duration)
    check(res, {
      'product detail 200':      r => r.status === 200,
      'product detail has data': r => !!JSON.parse(r.body).data,
    })
  }

  // Scenario 4: product variants
  else {
    const productId = randomOf(PRODUCT_IDS)
    const res = http.get(`${BASE_URL}/product/${productId}/variants`)
    detailDuration.add(res.timings.duration)
    check(res, {
      'variants 200':        r => r.status === 200,
      'variants is array':   r => Array.isArray(JSON.parse(r.body).data),
    })
  }

  sleep(1)
}
