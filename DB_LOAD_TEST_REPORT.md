# DB 부하테스트 & 커넥션 풀 최적화 리포트

> 작성일: 2026-08-16
> 목적: 로컬 환경에서 실제 Supabase DB에 의도적으로 부하를 줘서 장애 시나리오를 재현하고, 원인을 진단한 뒤 해결까지 검증한 기록.

---

## 테스트 환경

- **대상 API**: `GET /api/places/{placeId}/analysis-status` — 순수 DB 조회 1건, 외부 API(OpenAI/Google Maps/날씨) 호출 없음
- **부하 도구**: k6 (`k6/db-load-test.js`) — 0→60명 동시 사용자, 2분간 램핑
- **모니터링**: Prometheus + Grafana (로컬 네이티브 실행, `monitoring/prometheus.yml`)
- **DB**: 실제 Supabase Postgres (Free 티어, PgBouncer, 로컬 HikariCP 풀 max=3)
- **인증**: 사전 시딩된 테스트 계정(`dony615@naver.com`)으로 로그인 후 토큰 재사용

---

## 1차 진단 — DB 커넥션 풀 포화

### 원인 분석
- 60명 동시 부하에서 **요청 실패율 0%**, 응답시간도 평균 63ms/p95 174ms로 겉보기엔 정상이었음
- 하지만 Prometheus로 실측한 HikariCP 지표는 달랐음:
  - `hikaricp_connections_active` = **3/3 (풀 완전 포화)**
  - `hikaricp_connections_pending`(커넥션 대기 중인 요청) = **순간 최대 40건**
- 즉, 에러가 없었던 건 각 쿼리가 워낙 가볍고 HikariCP 커넥션 타임아웃(30초)이 넉넉했기 때문일 뿐, **실제로는 풀이 이미 한계까지 몰린 상태**였음. 쿼리가 조금만 무거워지거나 동시접속이 늘면 타임아웃 → 504 연쇄 장애로 바로 이어지는 구조
- 근본 원인: `analysis-status`/`summary` API는 프론트가 AI 분석 완료 여부를 확인하려고 **반복 폴링**하는 API인데, 매 호출마다 DB를 다시 조회하고 있었음

### 원인 해결 (1차 시도)
- `RedisConfig.java`에 캐시 설정 추가: `placeAnalysisStatus`(TTL 20초), `placeSummary`(TTL 10분) — 기존 `placeDetail` 캐시 패턴 재사용
- `PlaceService.getAnalysisStatus()`, `getPlaceSummary()`에 `@Cacheable` 적용
- TTL 20초로 잡은 이유: AI 분석 자체가 최소 수십 초 걸리는 작업이라, 상태 확인이 최대 20초 지연되는 건 사용자 체감에 영향 없음

---

## 2차 진단 — 캐싱을 적용했는데도 왜 그대로였나

### 원인 분석
- 캐시 적용 후 재테스트했는데 `hikaricp_connections_pending` 최대값이 **39건 → 거의 그대로**(개선 없음), 응답시간도 그대로였음
- `redis-cli keys "*"`로 확인해보니 캐시 키가 하나도 안 남아있었음 (TTL 만료 때문일 수도 있어서) → 앱 로그를 직접 grep해서 원인 특정
- 앱 로그에서 발견:
  ```
  [Cache] GET 실패 — 캐시 건너뜀 (cache=placeAnalysisStatus, key=k6-load-test-place):
  Could not read JSON: Cannot construct instance of `PlaceAnalysisStatusResponse`
  (no Creators, like default constructor, exist)
  ```
- **진짜 원인**: `PlaceAnalysisStatusResponse`, `PlaceSummaryResponse` 두 DTO가 `@Getter @Builder`만 있고 **기본 생성자가 없어서**, Redis에 저장(PUT)은 됐지만 다시 읽어올 때(GET) Jackson이 객체를 못 만들어 매번 실패 → `RedisConfig`의 에러 핸들러가 이 실패를 조용히 삼키고 실제 DB 메서드로 폴백 → **캐시가 있으나 마나였음**
- 이 DTO들은 원래 "서버 → 클라이언트로 나가기만" 하는 응답 객체라 지금까지 역직렬화될 일이 없었는데, Redis 캐싱을 붙이면서 "Redis → 서버로 다시 들어오는" 경로가 처음 생겨서 드러난 버그

### 원인 해결 (최종)
- `PlaceAnalysisStatusResponse`, `PlaceSummaryResponse`에 `@NoArgsConstructor` + `@AllArgsConstructor` 추가 → Jackson이 정상적으로 역직렬화 가능해짐

---

## 최종 검증 결과 (Before / After)

| 지표 | 캐싱 전 | 캐싱 적용(버그 있음) | 캐싱 적용(수정 후) |
|---|---|---|---|
| 응답시간 평균 | 63.66ms | 70.36ms | **3.38ms** |
| 응답시간 p95 | 174.56ms | 185.23ms | **5.21ms** |
| 처리량 (req/s) | 150.6 | 144.4 | **237.7** |
| HikariCP active 최대 | 3/3 (포화) | 3/3 (포화) | **1/3 (여유)** |
| HikariCP pending 최대 | 40건 대기 | 39건 대기 | **0건** |
| 실패율 | 0% | 0% | 0% |

**응답시간 약 20배 개선, DB 커넥션 대기(pending)는 완전히 해소됨.** 캐시 미스가 거의 없어서(같은 placeId를 20초 TTL 안에서 반복 조회) DB는 사실상 첫 요청 한 번만 타고, 나머지는 전부 Redis에서 응답.

---

## 배운 점 / 남은 과제
1. **에러율/평균 응답시간만 보면 병목을 놓친다** — 이번 케이스는 실패율 0%였지만 커넥션 풀은 이미 한계였음. HikariCP의 `active`/`pending` 지표를 같이 봐야 진짜 상태가 보임
2. **캐시를 붙였다고 끝이 아니다** — "에러 없이 넘어감"과 "제대로 동작함"은 다르다. 이번처럼 에러 핸들러가 실패를 조용히 삼키는 구조에서는 반드시 캐시 키가 실제로 쌓이는지, hit이 되는지 확인해야 함
3. ~~캐시 무효화는 아직 미구현~~ → **완료 (2026-08-16 추가 작업)**. `PlaceAnalysisService`에 이미 있던 `evictPlaceDetailCache()`를 `evictPlaceCaches()`로 확장해서 `placeDetail`뿐 아니라 `placeAnalysisStatus`, `placeSummary`도 함께 evict하도록 수정. 호출 시점 3곳: ① 분석 정상 완료 시 ② 분석 실패 후 PENDING 고착 방지용 fallback 값 저장 시(기존엔 누락돼 있었음) ③ 재분석(reanalyze) 시작 시. 실제 API 호출로 "캐시 채움 → reanalyze 호출 → 캐시 즉시 삭제"를 검증 완료 — TTL(20초) 만료를 기다리지 않고 상태 변경 즉시 반영됨
4. 이번 테스트는 로컬 환경 기준. 실제 서버(ECS 등)에 재배포하면 네트워크 지연/리소스 제한이 달라서 절대 수치는 다시 검증해야 하지만, 이번에 고친 **원인과 해결 방식(캐싱)은 환경에 관계없이 그대로 유효**함

---

## 변경된 파일
- `build.gradle` — `micrometer-registry-prometheus` 추가
- `application-local.yml` — `management.endpoints.web.exposure.include: health, prometheus` (local 전용)
- `src/main/java/.../config/SecurityConfig.java` — `/actuator/prometheus` permitAll 추가
- `src/main/java/.../config/RedisConfig.java` — `placeAnalysisStatus`, `placeSummary` 캐시 설정 추가
- `src/main/java/.../domain/place/service/PlaceService.java` — `@Cacheable` 적용
- `src/main/java/.../domain/place/dto/PlaceAnalysisStatusResponse.java`, `PlaceSummaryResponse.java` — `@NoArgsConstructor`/`@AllArgsConstructor` 추가 (Redis 역직렬화 버그 수정)
- `src/main/java/.../domain/place/service/external/PlaceAnalysisService.java` — `evictPlaceDetailCache()` → `evictPlaceCaches()`로 확장, 분석 완료/fallback/재분석 시점에 `placeAnalysisStatus`·`placeSummary` 캐시도 함께 무효화
- `monitoring/prometheus.yml`, `monitoring/grafana/provisioning/` — 로컬 APM 스택 설정
- `k6/db-load-test.js` — 부하테스트 스크립트
