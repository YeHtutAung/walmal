/**
 * Warehouse load test — TC-PERF-WAREHOUSE
 *
 * Scenarios (mix of WAREHOUSE_STAFF and ADMIN reads):
 *   1. listTasks      — GET /warehouse/tasks (ADMIN)
 *   2. getTask        — GET /warehouse/tasks/{id} (ADMIN) — uses IDs discovered in setup
 *   3. getFulfillment — GET /warehouse/fulfillments/{orderId} (WAREHOUSE_STAFF)
 *                       Uses the seeded order from V5 (c0000000-...-0001)
 *   4. locationList   — GET /inventory/locations (WAREHOUSE_MANAGER role)
 *
 * Write scenarios are omitted from the sustained load test because:
 *   - advance/pick/ship are one-way state transitions on real orders
 *   - Repeated execution would exhaust the fulfillment lifecycle and produce 409s
 *   - A separate smoke test (see README) covers the full fulfillment workflow
 *
 * Success criteria:
 *   p(95) < 500 ms for all reads
 *   error rate < 1 %
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend } from 'k6/metrics'
import { BASE_URL, CREDS, login, authHeaders } from './helpers.js'

// Seeded order from V5 migration (stable UUID)
const SEEDED_ORDER_ID = 'c0000000-0000-0000-0000-000000000001'

const readDuration = new Trend('warehouse_read_duration', true)

export const options = {
  stages: [
    { duration: '30s', target: 15 },
    { duration: '2m',  target: 15 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    warehouse_read_duration: ['p(95)<500'],
    http_req_failed:         ['rate<0.01'],
  },
}

export function setup() {
  // Login as admin to discover warehouse task IDs
  const adminTokens = login(CREDS.admin.username, CREDS.admin.password)
  if (!adminTokens) throw new Error('Admin login failed')

  const staffTokens = login(CREDS.warehouseStaff.username, CREDS.warehouseStaff.password)
  if (!staffTokens) throw new Error('Warehouse staff login failed')

  // Fetch first page of tasks so VUs can GET individual tasks by ID
  const res = http.get(
    `${BASE_URL}/warehouse/tasks?page=0&size=10`,
    authHeaders(adminTokens.accessToken),
  )
  let taskIds = []
  if (res.status === 200) {
    const body = JSON.parse(res.body)
    const content = body.data && body.data.content ? body.data.content : []
    taskIds = content.map(t => t.id).filter(Boolean)
  }

  return {
    adminToken: adminTokens.accessToken,
    staffToken: staffTokens.accessToken,
    taskIds,
  }
}

export default function (data) {
  const iter = __ITER % 4

  switch (iter) {
    // Scenario 1: list warehouse tasks (admin)
    case 0: {
      const page = Math.floor(Math.random() * 3)
      const res  = http.get(
        `${BASE_URL}/warehouse/tasks?page=${page}&size=20`,
        authHeaders(data.adminToken),
      )
      readDuration.add(res.timings.duration)
      check(res, { 'list tasks 200': r => r.status === 200 })
      break
    }

    // Scenario 2: get specific task (admin)
    case 1: {
      if (data.taskIds.length === 0) {
        // No tasks seeded — skip gracefully
        break
      }
      const taskId = data.taskIds[__ITER % data.taskIds.length]
      const res    = http.get(
        `${BASE_URL}/warehouse/tasks/${taskId}`,
        authHeaders(data.adminToken),
      )
      readDuration.add(res.timings.duration)
      // 404 is possible if task was deleted between setup and this iteration
      check(res, { 'get task 200 or 404': r => r.status === 200 || r.status === 404 })
      break
    }

    // Scenario 3: get fulfillment for the seeded order (warehouse staff)
    case 2: {
      const res = http.get(
        `${BASE_URL}/warehouse/fulfillments/${SEEDED_ORDER_ID}`,
        authHeaders(data.staffToken),
      )
      readDuration.add(res.timings.duration)
      // 404 is expected if the fulfillment record doesn't exist for the seeded order
      check(res, { 'fulfillment 200 or 404': r => r.status === 200 || r.status === 404 })
      break
    }

    // Scenario 4: list inventory locations (warehouse manager credential used for admin token here)
    case 3: {
      const res = http.get(`${BASE_URL}/inventory/locations`, authHeaders(data.adminToken))
      readDuration.add(res.timings.duration)
      check(res, { 'locations 200': r => r.status === 200 })
      break
    }

    default:
      break
  }

  sleep(1)
}
