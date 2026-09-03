import http from 'k6/http';
import { check, sleep } from 'k6';

// AI 서버 분리(recovery/stream) 부하가 무관한 일반 API(GET /api/trips)에 새는지 확인하는 부하테스트.
//
// 실행 방법 (베이스라인 먼저, 그다음 SSE 부하 포함):
//   1) 베이스라인만: k6 run -e ENABLE_SSE_LOAD=false -e TEST_EMAIL=... -e TEST_PASSWORD=... k6/sse-isolation-load-test.js
//   2) 100명:       k6 run -e ENABLE_SSE_LOAD=true -e SSE_TARGET_VUS=100 -e TRIP_ID=1 -e DAY=1 -e TEST_EMAIL=... -e TEST_PASSWORD=... k6/sse-isolation-load-test.js
//   3) 500명:       위와 동일하되 -e SSE_TARGET_VUS=500
//
// trips_baseline 시나리오(GET /api/trips)는 항상 고정 VU로 돌면서 p95를 측정하고,
// sse_load 시나리오(POST recovery/stream)는 ENABLE_SSE_LOAD=true일 때만 0→target으로 램핑됨.
// 같은 스크립트로 "SSE 부하 없음"과 "SSE 부하 있음"을 따로 돌려서 trips_baseline의 p95를 비교한다.
//
// ⚠️ recovery/stream은 실제로 Google Places API를 호출한다(캐시 불가 영역) — TRIP_ID/DAY로 지정한
// 일차에 야외(OUTDOOR) 장소가 없으면 Google 호출 없이 즉시 recovery_done으로 끝나서 비용이 안 든다.
// 격리 효과만 볼 목적이면 야외 장소가 없는 일차를 쓰는 걸 권장.

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;
const TRIP_ID = __ENV.TRIP_ID || '1';
const DAY = __ENV.DAY || '1';
const ENABLE_SSE_LOAD = (__ENV.ENABLE_SSE_LOAD || 'false') === 'true';
const SSE_TARGET_VUS = parseInt(__ENV.SSE_TARGET_VUS || '100', 10);

const scenarios = {
  trips_baseline: {
    executor: 'constant-vus',
    vus: 15,
    duration: '70s',
    exec: 'tripsBaseline',
  },
};

if (ENABLE_SSE_LOAD) {
  scenarios.sse_load = {
    executor: 'ramping-vus',
    startVUs: 0,
    startTime: '10s', // baseline이 먼저 10초 안정화된 뒤 SSE 부하 시작
    stages: [
      { duration: '20s', target: Math.round(SSE_TARGET_VUS / 4) },
      { duration: '20s', target: SSE_TARGET_VUS },
      { duration: '10s', target: 0 },
    ],
    exec: 'sseRecoveryStream',
  };
}

export const options = { scenarios };

export function setup() {
  if (!TEST_EMAIL || !TEST_PASSWORD) {
    throw new Error('TEST_EMAIL / TEST_PASSWORD 환경변수가 필요합니다 (-e 옵션으로 전달)');
  }
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: TEST_EMAIL, password: TEST_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const token = res.json('access_token');
  if (!token) throw new Error(`로그인 실패: ${res.status} ${res.body}`);
  return { token };
}

export function tripsBaseline(data) {
  const res = http.get(`${BASE_URL}/api/trips`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: 'trips_baseline' },
  });
  check(res, { '응답 200': (r) => r.status === 200 });
  sleep(0.2);
}

export function sseRecoveryStream(data) {
  const res = http.post(
    `${BASE_URL}/api/trips/${TRIP_ID}/days/${DAY}/recovery/stream`,
    null,
    {
      headers: { Authorization: `Bearer ${data.token}` },
      tags: { name: 'sse_recovery_stream' },
      timeout: '60s',
    }
  );
  check(res, { '응답 200 또는 정상 종료': (r) => r.status === 200 });
  sleep(0.1);
}
