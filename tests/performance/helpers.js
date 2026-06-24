/**
 * Shared helpers for k6 performance tests.
 * Import via: import { login, BASE_URL, LOCATION_ID, authHeaders } from './helpers.js'
 */
import http from 'k6/http'
import { check } from 'k6'

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1'

// Seeded IDs (from V4, V6, V9 migrations — stable dev data)
export const LOCATION_ID   = 'a0000000-0000-0000-0000-000000000001' // Main Warehouse
export const BUFFER_LOC_ID = 'a0000000-0000-0000-0000-000000000002' // Buffer
export const TERMINAL_ID   = 'b0000000-0000-0000-0000-000000000001' // Main Store Terminal 1

// Seeded product variant IDs (V9)
export const VARIANTS = {
  S24_256_BLK:  '20000000-0000-0000-0000-000000000001',
  S24_512_TIT:  '20000000-0000-0000-0000-000000000002',
  IP16_256_BLK: '20000000-0000-0000-0000-000000000003',
  IP16_512_NAT: '20000000-0000-0000-0000-000000000004',
  MBP14_SLV:    '20000000-0000-0000-0000-000000000005',
  TEE_M_WHT:    '20000000-0000-0000-0000-000000000006',
  TEE_L_BLK:    '20000000-0000-0000-0000-000000000007',
  JEAN_32_IND:  '20000000-0000-0000-0000-000000000008',
  JEAN_34_BLK:  '20000000-0000-0000-0000-000000000009',
}

// Seeded product IDs (V9)
export const PRODUCTS = {
  GALAXY_S24: '10000000-0000-0000-0000-000000000001',
  IPHONE_16:  '10000000-0000-0000-0000-000000000002',
  MACBOOK_14: '10000000-0000-0000-0000-000000000003',
  CREW_TEE:   '10000000-0000-0000-0000-000000000004',
  SLIM_JEANS: '10000000-0000-0000-0000-000000000005',
}

// Test credentials (V11, V12 migrations)
export const CREDS = {
  customer:         { username: 'customer_test',    password: 'TestPass123!' },
  admin:            { username: 'admin_test',        password: 'AdminPass123!' },
  warehouseManager: { username: 'warehouse_manager', password: 'wm123456' },
  warehouseStaff:   { username: 'warehouse_staff',   password: 'ws123456' },
  posOperator:      { username: 'pos_operator',      password: 'pos123456' },
}

/** POST /auth/login and return { accessToken, refreshToken } or null on failure. */
export function login(username, password) {
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' } },
  )
  const ok = check(res, { 'login 200': r => r.status === 200 })
  if (!ok) return null
  const body = JSON.parse(res.body)
  return { accessToken: body.accessToken, refreshToken: body.refreshToken }
}

/** Build request params with Authorization header. */
export function authHeaders(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  }
}

/** Standard JSON-only headers (no auth). */
export const jsonHeaders = { headers: { 'Content-Type': 'application/json' } }

/** Pick a random element from an array. */
export function randomOf(arr) {
  return arr[Math.floor(Math.random() * arr.length)]
}
