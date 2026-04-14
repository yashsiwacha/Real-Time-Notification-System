import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_USERNAME = __ENV.ADMIN_USERNAME || 'admin';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin123';
const LOGIN_EACH_ITERATION = (__ENV.LOGIN_EACH_ITERATION || 'false').toLowerCase() === 'true';
const USE_SETUP_TOKEN = (__ENV.USE_SETUP_TOKEN || 'true').toLowerCase() === 'true';
const TOKEN_REFRESH_ITERATIONS = Number(__ENV.TOKEN_REFRESH_ITERATIONS || '0');
const TARGET_EPS = Number(__ENV.TARGET_EPS || '500');
const TEST_DURATION = __ENV.TEST_DURATION || '3m';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || '300');
const MAX_VUS = Number(__ENV.MAX_VUS || '1200');

const acceptedRate = new Rate('accepted_rate');
const rateLimitedTotal = new Counter('rate_limited_total');
const serverErrorTotal = new Counter('server_error_total');

let cachedToken = null;
let lastTokenIteration = -1;

export const options = {
  scenarios: {
    notify_steady_500eps: {
      executor: 'constant-arrival-rate',
      rate: TARGET_EPS,
      timeUnit: '1s',
      duration: TEST_DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<800'],
    accepted_rate: ['rate>0.98']
  }
};

function loginAndGetToken() {
  const payload = JSON.stringify({
    username: ADMIN_USERNAME,
    password: ADMIN_PASSWORD
  });

  const response = http.post(`${BASE_URL}/api/auth/login`, payload, {
    headers: { 'Content-Type': 'application/json' }
  });

  if (response.status !== 200) {
    check(response, {
      'login status 200': (r) => r.status === 200
    });
    return null;
  }

  let token = null;
  try {
    token = response.json('token');
  } catch (e) {
    token = null;
  }

  const ok = check(response, {
    'login status 200': (r) => r.status === 200,
    'token returned': () => !!token
  });

  if (!ok) {
    return null;
  }

  return token;
}

function getToken() {
  if (LOGIN_EACH_ITERATION) {
    return loginAndGetToken();
  }

  const shouldRefresh =
    !cachedToken ||
    (TOKEN_REFRESH_ITERATIONS > 0 && __ITER - lastTokenIteration >= TOKEN_REFRESH_ITERATIONS);

  if (shouldRefresh) {
    cachedToken = loginAndGetToken();
    lastTokenIteration = __ITER;
  }

  return cachedToken;
}

export function setup() {
  if (LOGIN_EACH_ITERATION || !USE_SETUP_TOKEN) {
    return { setupToken: null };
  }

  return { setupToken: loginAndGetToken() };
}

export default function (data) {
  let token = data && data.setupToken ? data.setupToken : getToken();
  if (!token) {
    sleep(1);
    return;
  }

  const userId = `user-${__VU}`;
  const payload = JSON.stringify({
    userId,
    type: 'ORDER_STATUS',
    message: `Order update for ${userId}`,
    idempotencyKey: `vu-${__VU}-iter-${__ITER}`,
    metadata: {
      orderId: `${1000 + __VU}`,
      region: 'in'
    }
  });

  const response = http.post(`${BASE_URL}/api/notifications`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`
    }
  });

  if (response.status === 401 && !LOGIN_EACH_ITERATION) {
    cachedToken = loginAndGetToken();
    token = cachedToken;
  }

  acceptedRate.add(response.status === 202);
  if (response.status === 429) {
    rateLimitedTotal.add(1);
  }
  if (response.status >= 500) {
    serverErrorTotal.add(1);
  }

  check(response, {
    'publish status 202': (r) => r.status === 202
  });

  sleep(0.1);
}
