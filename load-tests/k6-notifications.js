import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_USERNAME = __ENV.ADMIN_USERNAME || 'admin';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin123';
const LOGIN_EACH_ITERATION = (__ENV.LOGIN_EACH_ITERATION || 'false').toLowerCase() === 'true';
const USE_SETUP_TOKEN = (__ENV.USE_SETUP_TOKEN || 'true').toLowerCase() === 'true';
const TOKEN_REFRESH_ITERATIONS = Number(__ENV.TOKEN_REFRESH_ITERATIONS || '0');

let cachedToken = null;
let lastTokenIteration = -1;

export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '1m', target: 1000 },
    { duration: '2m', target: 5000 },
    { duration: '2m', target: 10000 },
    { duration: '30s', target: 0 }
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<900']
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

  check(response, {
    'publish status 202': (r) => r.status === 202
  });

  sleep(0.1);
}
