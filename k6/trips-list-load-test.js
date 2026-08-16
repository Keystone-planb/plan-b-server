import http from 'k6/http';
import { check, sleep } from 'k6';

// GET /api/trips (여행 목록) 부하테스트 — N+1 제거 후 실제로 버티는지 확인용
// 실행: k6 run -e TEST_EMAIL=... -e TEST_PASSWORD=... k6/trips-list-load-test.js
export const options = {
  scenarios: {
    trips_list: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 20 },
        { duration: '30s', target: 50 },
        { duration: '20s', target: 0 },
      ],
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

export function setup() {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: TEST_EMAIL, password: TEST_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const token = res.json('access_token');
  if (!token) throw new Error(`로그인 실패: ${res.status} ${res.body}`);
  return { token };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/api/trips`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  check(res, { '응답 200': (r) => r.status === 200 });
  sleep(0.2);
}
