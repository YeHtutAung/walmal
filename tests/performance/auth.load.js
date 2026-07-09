/**
 * Auth load test — TC-PERF-AUTH
 *
 * Scenarios (round-robin per iteration):
 *   1. login        — POST /auth/login with valid credentials
 *   2. refresh      — POST /auth/refresh with THIS VU's personal refreshToken
 *   3. invalidLogin — POST /auth/login with non-existent username (expects 404)
 *
 * Success criteria:
 *   p(95) < 500 ms for login and refresh
 *   login error rate < 1 % (expected 404s excluded)
 *
 * Design notes:
 *   - Each VU maintains its own refresh token in `vuRefreshToken` (module-level).
 *     Scenario 1 seeds it on each login iteration; scenario 2 uses and rotates it.
 *     This avoids the single-use-token failure that occurs when all VUs share the
 *     one token returned by setup() — only the first VU to call refresh wins.
 *   - The backend returns 404 for non-existent usernames (not 401).
 *     Scenario 3 checks for 404 accordingly.
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate } from 'k6/metrics'
import { BASE_URL, CREDS, jsonHeaders } from './helpers.js'

const loginDuration   = new Trend('auth_login_duration', true)
const refreshDuration = new Trend('auth_refresh_duration', true)
const loginErrors     = new Rate('auth_login_errors')

// Per-VU state: each VU keeps its own refresh token (not shared via setup data).
// Module-level variables in k6 are initialised once per VU in the init stage.
let vuRefreshToken = null

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '2m',  target: 20 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    auth_login_duration:   ['p(95)<500'],
    auth_refresh_duration: ['p(95)<500'],
    auth_login_errors:     ['rate<0.01'],
  },
}

export function setup() {
  // Verify the backend is reachable before the load test begins.
  // Intentionally returns {} — per-VU tokens are managed in default(), not here.
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username: CREDS.customer.username, password: CREDS.customer.password }),
    jsonHeaders,
  )
  check(res, { 'setup login 200': r => r.status === 200 })
  return {}
}

export default function () {
  const iter = __ITER % 3

  // Scenario 1: happy-path login — seeds this VU's personal refresh token
  if (iter === 0) {
    const cred = (__VU % 2 === 0) ? CREDS.customer : CREDS.admin
    const res = http.post(
      `${BASE_URL}/auth/login`,
      JSON.stringify({ username: cred.username, password: cred.password }),
      jsonHeaders,
    )
    loginDuration.add(res.timings.duration)
    const ok = check(res, {
      'login 200':              r => r.status === 200,
      'login has accessToken':  r => { try { return !!JSON.parse(r.body).accessToken } catch (e) { return false } },
      'login has refreshToken': r => { try { return !!JSON.parse(r.body).refreshToken } catch (e) { return false } },
    })
    loginErrors.add(!ok)
    if (res.status === 200) {
      try { vuRefreshToken = JSON.parse(res.body).refreshToken } catch (e) { /* ignore */ }
    }
  }

  // Scenario 2: token refresh — uses THIS VU's personal token, not shared setup data
  else if (iter === 1) {
    if (!vuRefreshToken) {
      // VU hasn't completed a login iteration yet — skip gracefully this cycle
      sleep(1)
      return
    }
    const res = http.post(
      `${BASE_URL}/auth/refresh`,
      JSON.stringify({ refreshToken: vuRefreshToken }),
      jsonHeaders,
    )
    refreshDuration.add(res.timings.duration)
    check(res, {
      'refresh 200':             r => r.status === 200,
      'refresh has accessToken': r => { try { return !!JSON.parse(r.body).accessToken } catch (e) { return false } },
    })
    // Rotate stored token on success; clear on failure so the next login re-seeds it
    if (res.status === 200) {
      try { vuRefreshToken = JSON.parse(res.body).refreshToken } catch (e) { vuRefreshToken = null }
    } else {
      vuRefreshToken = null
    }
  }

  // Scenario 3: non-existent user — backend returns 404, NOT counted as a load error
  else {
    const res = http.post(
      `${BASE_URL}/auth/login`,
      JSON.stringify({ username: `ghost_vu${__VU}`, password: 'WrongPass99' }),
      jsonHeaders,
    )
    check(res, { 'invalid login 404': r => r.status === 404 })
  }

  sleep(1)
}
