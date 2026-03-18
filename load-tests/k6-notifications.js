import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_USERNAME = __ENV.ADMIN_USERNAME || 'admin';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin123';

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

  const ok = check(response, {
    'login status 200': (r) => r.status === 200,
    'token returned': (r) => !!r.json('token')
  });

  if (!ok) {
    return null;
  }

  return response.json('token');
}

export default function () {
  const token = loginAndGetToken();
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

  check(response, {
    'publish status 202': (r) => r.status === 202
  });

  sleep(0.1);
}
