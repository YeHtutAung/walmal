/**
 * Auth load test — TC-PERF-AUTH
 *
 * Scenarios (round-robin per iteration):
 *   1. login        — POST /auth/login with valid credentials
 *   2. refresh      — POST /auth/refresh with a valid refreshToken
 *   3. invalidLogin — POST /auth/login with wrong password (expects 401, not an error)
 *
 * Success criteria:
 *   p(95) < 500 ms for login and refresh
 *   login error rate < 1 % (expected 401s excluded)
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate } from 'k6/metrics'
import { BASE_URL, CREDS, jsonHeaders } from './helpers.js'

const loginDuration   = new Trend('auth_login_duration', true)
const refreshDuration = new Trend('auth_refresh_duration', true)
const loginErrors     = new Rate('auth_login_errors')

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
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username: CREDS.customer.username, password: CREDS.customer.password }),
    jsonHeaders,
  )
  check(res, { 'setup login 200': r => r.status === 200 })
  const body = JSON.parse(res.body)
  return { refreshToken: body.refreshToken }
}

export default function (data) {
  const iter = __ITER % 3

  // Scenario 1: happy-path login
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
      'login has accessToken':  r => !!JSON.parse(r.body).accessToken,
      'login has refreshToken': r => !!JSON.parse(r.body).refreshToken,
    })
    loginErrors.add(!ok)
  }

  // Scenario 2: token refresh
  else if (iter === 1) {
    const res = http.post(
      `${BASE_URL}/auth/refresh`,
      JSON.stringify({ refreshToken: data.refreshToken }),
      jsonHeaders,
    )
    refreshDuration.add(res.timings.duration)
    check(res, {
      'refresh 200':             r => r.status === 200,
      'refresh has accessToken': r => !!JSON.parse(r.body).accessToken,
    })
  }

  // Scenario 3: invalid credentials — 401 expected, NOT counted as error
  else {
    const res = http.post(
      `${BASE_URL}/auth/login`,
      JSON.stringify({ username: 'no_such_user', password: 'WrongPass99!' }),
      jsonHeaders,
    )
    check(res, { 'invalid login 401': r => r.status === 401 })
  }

  sleep(1)
}
