import http from 'k6/http';
import { check, sleep } from 'k6';

// 실행 예시:
// k6 run -e TEST_EMAIL=you@test.com -e TEST_PASSWORD=yourpassword k6/db-load-test.js
//
// VUs(동시 사용자 수)를 늘려가며 로컬 HikariCP 풀(max=3)이 언제 고갈되는지 확인하는 용도.
// 대상 API는 DB만 조회하고 외부 API(OpenAI/Google Maps/날씨)는 호출하지 않음.
export const options = {
  scenarios: {
    db_overload: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '30s', target: 30 },
        { duration: '30s', target: 60 },
        { duration: '30s', target: 0 },
      ],
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;
const PLACE_ID = __ENV.PLACE_ID || 'k6-load-test-place';

export function setup() {
  if (!TEST_EMAIL || !TEST_PASSWORD) {
    throw new Error('TEST_EMAIL / TEST_PASSWORD 환경변수가 필요합니다 (-e 옵션으로 전달)');
  }

  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: TEST_EMAIL, password: TEST_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(res, { '로그인 성공': (r) => r.status === 200 });

  const token = res.json('access_token');
  if (!token) {
    throw new Error(`로그인 실패: ${res.status} ${res.body}`);
  }
  return { token };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/api/places/${PLACE_ID}/analysis-status`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });

  check(res, { '응답 200': (r) => r.status === 200 });
  sleep(0.1);
}
