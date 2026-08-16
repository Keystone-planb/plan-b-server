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

## 추가 최적화 — 여행 목록(`GET /api/trips`) N+1 제거

### 원인 분석
- `TripListResponse.from(trip)`이 `trip.getItineraries().stream().mapToInt(it -> it.getPlaces().size())`로 장소 개수를 셌는데, `getPlaces()`가 lazy 컬렉션이라 **이티너리 1개당 SELECT 쿼리 1번**씩 발생
- 여행 T개 × 일차 D개 구조라 최악의 경우 T×D번 쿼리가 추가로 나가는 전형적 N+1. `GET /api/trips`는 앱 첫 화면(여행 목록)에서 항상 호출되는 API라 영향 범위가 큼

### 원인 해결
- `TripPlaceRepository`에 `countByItineraryIds(List<Long>)` 추가 — `itinerary_id IN (...)` + `GROUP BY`로 전체 개수를 한 번에 집계
- `TripService.getMyTrips()`: 조회된 모든 trip의 itineraryId를 모아 위 쿼리 1회 호출 → `Map<Long,Integer>`로 만들어 각 trip에 개수 매핑
- `TripListResponse.from(trip, placeCount)`으로 시그니처 변경 — lazy 컬렉션을 더 이상 서비스 계층 밖에서 직접 건드리지 않음
- **검증**: `show-sql`로 실제 쿼리 로그 확인 — trip_places를 향한 쿼리가 이티너리 개수와 무관하게 **정확히 1번**(GROUP BY, IN절에 전체 itineraryId 포함)만 실행됨을 확인

## 추가 최적화 — 장소 교체/재계산 로직의 반복 조회 제거

### 원인 분석
- `TripService.replaceTripPlace()`와 `TripService.recalculateByDistanceMatrix()`(confirmOptimize 경로) 둘 다 "이후 일정 목록을 순회하면서 각 장소를 `placeRepository.findByGooglePlaceId()`로 하나씩 조회" 하는 동일한 패턴 → 순회 대상이 N개면 쿼리도 N번
- 같은 코드베이스 안에 이미 배치조회 메서드(`PlaceRepository.findAllByGooglePlaceIdIn`)가 존재하고 다른 곳(장소 추가 시 카테고리 조회 등)에서는 이미 쓰이고 있었는데, 이 두 곳만 개별조회로 남아있었음

### 원인 해결
- 루프 시작 전에 `subsequent`/`subsequentPlaces`의 placeId를 모아 `findAllByGooglePlaceIdIn()` 1회 호출 → `Map<String, Place>`로 변환
- 루프 안에서는 DB 조회 대신 Map 조회(`placeByGoogleId.get(...)`)만 수행 — 기존의 null/좌표없음 체크 시 순회 중단(break)하는 로직은 그대로 유지
- **참고**: 이 두 메서드는 여행 일정 데이터 + Google Distance Matrix API 호출이 얽혀있어, 이번 세션에서는 부하테스트로 실측 검증하지 못했음(외부 API 키 비활성 상태). 컴파일 확인 + 코드베이스에 이미 검증된 동일 패턴을 기계적으로 적용한 것으로 리스크를 낮게 판단

## 추가 최적화 — 여정 복구 확정(`confirmRecovery`)의 반복 upsert 조회 제거

### 원인 분석
- `confirmRecovery()`가 요청받은 장소 목록(`request.getPlaces()`)을 순회하면서, 장소가 교체된 항목마다 `placeRepository.findByGooglePlaceId()`로 기존 Place를 찾고 없으면 새로 만드는 upsert를 개별 수행 → 교체된 장소 수만큼 조회 쿼리 발생
- 호출 빈도 자체는 앞의 두 건보다 낮지만(여정 복구 확정 시점에만) 동일한 반복조회 패턴이라 같이 정리

### 원인 해결
- 루프 진입 전 `request.getPlaces()`의 placeId를 모아 `findAllByGooglePlaceIdIn()` 1회로 기존 Place를 `Map<String, Place>`에 미리 인덱싱
- 루프 안에서는 이 맵에서 조회하고, 없으면 기존과 동일하게 새 Place를 생성(create-if-missing 로직 그대로 유지) → 동작은 완전히 동일, 쿼리 횟수만 감소

## 추가 최적화 — 여행 목록(`GET /api/trips`) 커넥션 풀 포화 해결

### 원인 분석
- N+1을 없앤 뒤에도, 이 API에 0→50명 동시 부하를 주면 응답시간이 평균 1.35초/p95 2.55초까지 치솟고 `hikaricp_connections_pending`이 다시 최대 41건까지 쌓임
- 동시접속 없이 단독 요청했을 때는 0.15~0.24초 정도라, **쿼리 자체가 느린 게 아니라 로컬 커넥션 풀이 3개뿐이라 줄서서 기다리는 것**이 원인으로 확인됨 (Supabase Free 티어 제약을 반영한 값이라 풀 크기 자체를 늘리는 건 답이 아님)
- `analysis-status`처럼 모두가 같은 값을 보는 API가 아니라 유저별로 다른 개인 데이터라, 단순 TTL 캐싱만으로는 "방금 여행 추가했는데 목록에 안 보임" 같은 문제가 생길 수 있어 추가 설계가 필요했음

### 원인 해결
- `RedisConfig`에 `tripList` 캐시 추가 (TTL 30초, 안전망 용도)
- `TripService.getMyTrips()`에 `@Cacheable(key = "#email + ':' + #status")` 적용 — 유저+상태필터 조합별로 캐싱
- **즉시 무효화**: `createTrip`/`updateTrip`/`deleteTrip`/`addLocation`/`removeTripPlace` — 목록에 실제로 영향을 주는 5개 지점에 `evictTripListCache(email)` 추가. TTL 만료를 기다리지 않고 변경 즉시 최신화되므로 "방금 바뀐 게 안 보이는" 문제 없음
- **또 같은 버그 발견**: `List<TripListResponse>`를 `@Cacheable` 반환값으로 그대로 캐싱했더니, 이번엔 (기본 생성자 문제가 아니라) Jackson이 최상위 제네릭 컬렉션의 타입 정보를 못 살려서 `Unexpected token (START_ARRAY), expected VALUE_STRING` 에러로 매번 캐시 조회가 조용히 실패함. `TripListCacheEntry`(List를 감싼 POJO 래퍼)로 한 번 감싸서 해결 — 컨트롤러 쪽 API 응답 형태(`List<TripListResponse>`)는 그대로 유지, 캐싱 레이어에서만 래핑/언래핑
- **검증**: 응답시간 평균 1.35s→12.36ms(약 110배), p95 2.55s→4.9ms(약 520배), 처리량 16→116 req/s, HikariCP pending 최대 41건→9건(초반 동시 캐시미스 구간 제외하면 사실상 해소)

## 변경된 파일
- `build.gradle` — `micrometer-registry-prometheus` 추가
- `application-local.yml` — `management.endpoints.web.exposure.include: health, prometheus` (local 전용)
- `src/main/java/.../config/SecurityConfig.java` — `/actuator/prometheus` permitAll 추가
- `src/main/java/.../config/RedisConfig.java` — `placeAnalysisStatus`, `placeSummary` 캐시 설정 추가
- `src/main/java/.../domain/place/service/PlaceService.java` — `@Cacheable` 적용
- `src/main/java/.../domain/place/dto/PlaceAnalysisStatusResponse.java`, `PlaceSummaryResponse.java` — `@NoArgsConstructor`/`@AllArgsConstructor` 추가 (Redis 역직렬화 버그 수정)
- `src/main/java/.../domain/place/service/external/PlaceAnalysisService.java` — `evictPlaceDetailCache()` → `evictPlaceCaches()`로 확장, 분석 완료/fallback/재분석 시점에 `placeAnalysisStatus`·`placeSummary` 캐시도 함께 무효화
- `src/main/java/.../domain/trip/repository/TripPlaceRepository.java` — `countByItineraryIds()` 배치 집계 쿼리 추가
- `src/main/java/.../domain/trip/dto/TripListResponse.java`, `TripService.java` — 여행 목록 조회 N+1 제거 (`GET /api/trips`)
- `src/main/java/.../domain/trip/service/TripService.java` — `replaceTripPlace()`, `recalculateByDistanceMatrix()`의 반복 `findByGooglePlaceId` 호출을 배치조회로 변경
- `src/main/java/.../domain/trip/dto/TripListCacheEntry.java` (신규) — `tripList` 캐시 직렬화용 래퍼
- `src/main/java/.../domain/trip/service/TripService.java`, `TripController.java`, `RedisConfig.java` — `GET /api/trips` 캐싱 + 5개 지점 즉시 무효화
- `monitoring/prometheus.yml`, `monitoring/grafana/provisioning/` — 로컬 APM 스택 설정
- `k6/db-load-test.js` — 부하테스트 스크립트
