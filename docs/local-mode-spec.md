# 현지 모드 (틈새 추천) — 기능 명세서

> **확정 사항**
> - 이동수단: `trip.transport_mode` 컬럼 사용
> - 틈새 기준: **30분 미만 갭은 제외** (30분 이상만 감지)
> - 추천 응답: **SSE 스트리밍 적용** (`/stream` 방식)

---

## 목차
1. [기능 개요](#1-기능-개요)
2. [DB 변경](#2-db-변경)
3. [API 명세](#3-api-명세)
4. [서버 내부 동작](#4-서버-내부-동작)
5. [개발 범위 (파일 목록)](#5-개발-범위)
6. [사전 필요 데이터 — 기능별](#6-사전-필요-데이터)
7. [최소 테스트 데이터 SQL](#7-최소-테스트-데이터-sql)
8. [결과 0건 시나리오 대응표](#8-결과-0건-시나리오-대응표)

---

## 1. 기능 개요

### 한 줄 요약
일정 A ↔ 일정 B 사이 **빈 시간(30분 이상)을 자동 감지**해서, 그 자리에 딱 맞는 짧은 스팟을 AI가 실시간으로 추천해주는 기능

### 사용자 흐름
```
일정 화면에서 텀 발견
        │
        ▼
시스템이 자동 표시: "1시간 30분 비어있음" + [AI 틈새 추천] 버튼
        │
        ▼
버튼 클릭 → 자동 세팅:
  ├─ 위치: 직전 일정 A의 장소 기준
  ├─ 가용시간: 텀 시간 − 이동시간 − 안전여유 10분
  └─ 영업 필터: 현재 시각 기준 영업 중
        │
        ▼
SSE 스트리밍으로 카드 1개씩 등장 (최대 5개)
  event:progress  → 분석 시작 즉시
  event:place     → 분석 완료된 장소 순서대로
  event:done      → 완료
```

---

## 2. DB 변경

### 변경 테이블: `trip` (컬럼 1개 추가)

```sql
ALTER TABLE trip
  ADD COLUMN transport_mode varchar(20)
  CONSTRAINT trip_transport_mode_check
    CHECK (transport_mode IS NULL OR transport_mode IN ('WALK', 'TRANSIT', 'CAR'));
```

| 항목 | 내용 |
|------|------|
| NULL 허용 | ✅ 기존 trip은 NULL 유지 → 서버에서 WALK로 자동 폴백 |
| 허용값 | `WALK` / `TRANSIT` / `CAR` |
| 다른 테이블 변경 | 없음 (notifications, user_preference 이전 작업분 그대로) |

---

## 3. API 명세

### 기본 정보
- **서버**: `https://api-dev.planb-travel.cloud`
- **인증**: 모든 🔒 API → `Authorization: Bearer {access_token}`

---

### 🚶 이동수단 관리

#### GET /api/trips/{tripId}/transport-mode 🔒 — 이동수단 조회

```json
// Response 200
{
  "tripId": 42,
  "transportMode": "CAR",   // null 가능 (미설정 시)
  "effectiveMode": "CAR"    // null이면 WALK로 폴백된 실제 적용값
}
```

#### PATCH /api/trips/{tripId}/transport-mode 🔒 — 이동수단 변경

> 변경 즉시 해당 trip의 모든 이후 추천에 자동 반영

```json
// Request
{ "transportMode": "TRANSIT" }   // WALK | TRANSIT | CAR

// Response 200
{
  "tripId": 42,
  "previousMode": "CAR",
  "transportMode": "TRANSIT"
}
```

---

### ⏱️ 틈새 감지 및 추천

#### GET /api/trips/{tripId}/gaps 🔒 — 틈새 목록 조회

> 30분 이상인 빈 시간만 반환. 같은 날짜 일정 사이만 감지.

**Query Parameter**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `mode` | enum | X | 미지정 시 trip.transportMode → WALK 폴백 |

```json
// Response 200
[
  {
    "beforePlanId": 12,
    "beforePlanTitle": "성수 카페",
    "beforePlanEndTime": "2026-05-04T13:00:00",
    "beforePlaceLat": 37.544,
    "beforePlaceLng": 127.054,
    "afterPlanId": 15,
    "afterPlanTitle": "롯데월드타워",
    "afterPlanStartTime": "2026-05-04T15:00:00",
    "afterPlaceLat": 37.5126,
    "afterPlaceLng": 127.1025,
    "gapMinutes": 120,
    "availableMinutes": 96,
    "transportMode": "TRANSIT",
    "estimatedTravelMinutes": 14
  }
]
```

**가용시간 계산 공식**
```
availableMinutes = gapMinutes
                 − (Haversine(A, B) / 이동속도)
                 − 10분(안전여유)

이동속도: WALK=80m/분, TRANSIT=350m/분, CAR=500m/분
```

---

#### POST /api/trips/{tripId}/gaps/recommend/stream 🔒 — 틈새 추천 (SSE 스트리밍)

> 분석 완료된 장소부터 1개씩 실시간 push. EventSource 미사용 — fetch API로 구현.

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `userId` | Long | O | 개인화 학습 반영용 |
| `tripId` | Long | O | path와 일치해야 함 |
| `beforePlanId` | Long | O | 갭 직전 일정 (위치 기준) |
| `afterPlanId` | Long | O | 갭 직후 일정 (길목 보너스용) |
| `transportMode` | enum | X | 일회성 override. 미설정 시 trip.mode |
| `radiusMinute` | int | X | 미설정 시 `availableMinutes / 3` 자동 계산 |

```json
// Request 예시
{
  "userId": 1,
  "tripId": 42,
  "beforePlanId": 12,
  "afterPlanId": 15,
  "transportMode": "TRANSIT",
  "radiusMinute": 25
}
```

**SSE 이벤트 흐름 (기존 추천 스트리밍과 동일)**

```
event:progress  →  { "message": "AI가 틈새 장소를 분석 중입니다...", "total": 5 }
event:place     →  PlaceResult JSON (분석 완료 순서대로, 최대 5회)
event:done      →  [DONE]
```

**프론트엔드 구현 예시**
```javascript
const response = await fetch(
  `https://api-dev.planb-travel.cloud/api/trips/${tripId}/gaps/recommend/stream`,
  {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream'
    },
    body: JSON.stringify({ userId, tripId, beforePlanId, afterPlanId })
  }
);

const reader = response.body.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;

  const lines = decoder.decode(value).split('\n');
  let eventName = '';
  for (const line of lines) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim();
    if (line.startsWith('data:')) {
      const data = line.slice(5).trim();
      if (eventName === 'progress') showSkeleton();
      else if (eventName === 'place') appendPlaceCard(JSON.parse(data));
      else if (eventName === 'done')  hideLoading();
    }
  }
}
```

---

### 🗺️ 기존 일반 추천 — 변경 사항

#### POST /api/recommendations — 변경된 필드

| 필드 | 변경 내용 |
|------|----------|
| `walk` (boolean) | ❌ 제거됨 |
| `transportMode` | 🆕 추가 — `WALK` / `TRANSIT` / `CAR` (선택, 미설정 시 trip.mode) |
| `mustBeOpenAt` | 🆕 추가 — 특정 시각 영업중 필터 ISO-8601 형식 (선택) |

> ⚠️ `walk` 필드 제거로 기존 프론트 연동 코드 수정 필요

---

## 4. 서버 내부 동작

### 이동수단 우선순위 (모든 추천 공통)
```
1순위: 요청에 transportMode 명시
2순위: trip.transport_mode 저장값
3순위: WALK (기본 폴백)
```

### 추천 후보 사전 컷 4단계

Google Nearby Search 결과 20개에서 아래 순서로 필터링:

```
원본 20개
    │
    ▼  1단계: 블랙리스트 타입 제거
    │  convenience_store, political, hospital, school,
    │  police, cemetery, route, parking, atm 등
    │
    ▼  2단계: 이름 키워드 제거
    │  "아파트 / 빌라 / 오피스텔 / 단지 / 지하차도 /
    │   터널 / 사거리 / 삼거리 / 교차로 / 육교 / 주민센터" 포함 시 컷
    │
    ▼  3단계: 리뷰 수 부족 컷
    │  user_ratings_total < 5 → 신뢰성 부족
    │
    ▼  4단계: 화이트리스트 매칭 (필수 통과)
       [음식] cafe, bakery, restaurant, bar, meal_takeaway, night_club
       [관광] tourist_attraction, museum, art_gallery, library, aquarium, zoo
       [쇼핑] shopping_mall, department_store, book_store
       [액티비티] amusement_park, movie_theater, bowling_alley, spa, gym
       [자연] park, natural_feature, beach, campground
       [시장] market
       ※ establishment, point_of_interest 만 있는 generic 가게 → 컷
    │
    ▼
AI 분석 → 영업상태/영업시간/중복일정 필터 → 점수 캐시 → 정렬 → top 5
```

### 점수 계산 공식
```
finalScore = baseScore                        (rating × log(reviewCount+1))
           × filterMultiplier                 (Space/Type 일치 시 ×1.5~2.0)
           × distancePenalty                  (반경 초과 시 감산)
           × personalizationMultiplier        (mood 학습 0.7~2.0)
           × (detourRatio < 1.2 ? 2.5 : 1.0) (길목 보너스)
```

### Smart Expansion (결과 부족 시 자동 확장)
```
candidates.size() < 5 AND radiusMinute < 60
  → 반경 × 1.5 후 재검색 (재귀, 주거 밀집 지역 대응)
```

---

## 5. 개발 범위

### 신규 생성 파일 (8개)

| 파일 | 역할 |
|------|------|
| `TransportMode.java` | 이동수단 Enum (WALK / TRANSIT / CAR) |
| `GapInfo.java` | 틈새 정보 응답 DTO |
| `GapRecommendationRequest.java` | 틈새 추천 요청 DTO |
| `OpeningHoursService.java` | 영업시간 기반 필터 서비스 |
| `GapDetectionService.java` | 일정 간 틈새 감지 서비스 |
| `GapRecommendationService.java` | 틈새 추천 실행 서비스 (SSE) |
| `GapController.java` | 틈새 API 컨트롤러 |
| `TransportModeController.java` | 이동수단 API 컨트롤러 |

### 수정 파일 (7개)

| 파일 | 변경 내용 |
|------|----------|
| `Trip.java` | `transportMode` 컬럼 추가 |
| `RecommendRequest.java` | `walk` 제거 → `transportMode`, `mustBeOpenAt` 추가 |
| `UserContext.java` | `walk` 제거 → `transportMode`, `mustBeOpenAt` 추가, `getSpeedKmPerMin()` 헬퍼 |
| `ScoringStrategy.java` | 이동속도 기반 거리 계산 적용 |
| `RecommendationService.java` | 사전 컷 4단계 + trip.mode 자동 로드 + 점수 캐시 |
| `UserPreferenceService.java` | `loadMoodScores()` + `getPreferenceMultiplier()` 추가 |
| `WeatherScheduler.java` | `walk(false)` 제거 → `trip.transportMode` 자동 사용 |

---

## 6. 사전 필요 데이터

### 0. 공통 인프라 — 이게 없으면 어떤 추천도 동작 안 함

#### 외부 API 키 (`application.yml`)

| 키 | 사용 기능 | 미설정 시 |
|----|-----------|----------|
| `google.api.key` | Google Places API (검색·상세) | 모든 추천 0건 |
| `openai.api.key` | 리뷰 → AI 태그 분석 | 신규 장소 분석 실패 → space/type/mood = MIX/FOOD/LOCAL 기본값 |
| `naver.client.id` / `naver.client.secret` | 네이버 블로그 리뷰 수집 | 리뷰 0건, AI 분석 정확도 저하 |
| `instagram.api.key` | 인스타 리뷰 (선택) | 거의 항상 rate limit — 없어도 동작 |
| `openweather.api.key` | 기능 4 강수확률 조회 | 강수확률 0% 처리 → 알림 미발송 |

#### DB 기본 테이블

| 테이블 | 최소 필요 데이터 | 비고 |
|--------|----------------|------|
| `users` | 1행 이상 | userId가 외래키 |
| `trip` | 1행 이상 | 모든 추천이 tripId 기준 |
| `plan` | 2행 이상 (같은 trip) | 일정 없으면 추천도 갭도 없음 |
| `places` | (자동 채워짐) | Google Nearby Search 결과가 자동 INSERT |

#### Place 엔티티 자동 채워지는 필드

| 필드 | 채워지는 시점 | 쓰이는 곳 |
|------|------------|----------|
| `latitude`, `longitude` | Google 검색 시 | 거리 계산, 동선 보너스 |
| `category` | Google types[0] | keepOriginalCategory 모드 |
| `space`, `type`, `mood` | OpenAI 분석 후 | 필터, 개인화 |
| `rating`, `userRatingsTotal` | Google 검색 시 | baseScore, 사전 컷 |
| `businessStatus` | Google Details | 영업 상태 필터 |
| `openingHours` | Google Details | 영업시간 필터 |
| `googlePlaceId` | Google 검색 시 | 중복 방지 키 (UNIQUE) |

---

### 1. 일반 SOS 추천 (POST /api/recommendations)

**호출 시 필수 입력**

| 필드 | 필수 | 없으면 |
|------|------|-------|
| `userId` | O | 개인화 multiplier 1.0 (학습 미반영) |
| `tripId` | O | trip.mode 상속 안 됨 + 같은 여행 중복 제외 안 됨 |
| `currentLat`, `currentLng` | O | Google 검색 불가 |
| `radiusMinute` | O | 반경 0 → 결과 없음 |
| `selectedType` 또는 `keepOriginalCategory=true + currentPlanId` | O | searchCategory null → 노이즈 폭증 |
| `currentPlanId` | △ | `keepOriginalCategory=true` 일 때만 필수 |
| `currentPlanStartTime` | △ | `considerNextPlan=true` 일 때 필수 |
| `transportMode` | X | trip.mode → WALK 폴백 |
| `mustBeOpenAt` | X | 미설정 시 영업시간 검사 안 함 |

---

### 2. 날씨 알림 (자동 스케줄러)

**트리거**: `@Scheduled(fixedRate = 4시간)` — 별도 호출 불필요

**알림 발생 조건 (모두 충족해야)**
```
plan.startTime ∈ [현재, 현재+24시간]
  AND plan.place.space ≠ INDOOR
  AND OpenWeather POP(장소 좌표, startTime) ≥ 70%
  AND 같은 plan에 24시간 내 기발송 알림 없음
```

**사전 필요 데이터**

| 항목 | 필수 | 없으면 |
|------|------|-------|
| `users` 행 | O | 알림 생성 불가 |
| 24시간 내 `plan` 행 | O | 스캔 대상 0건 |
| `plan.place.latitude/longitude` | O | 강수확률 조회 스킵 |
| `plan.place.space` | O | INDOOR면 알림 안 만듦 |
| `openweather.api.key` | O | 강수확률 0% → 알림 미발송 |

---

### 3. Mood 개인화 학습 (기능 5)

**학습 트리거**

| 트리거 | 호출 방식 | 효과 |
|--------|----------|------|
| 알림 카드에서 일정 교체 | 서버 자동 | 새 place.mood +1.0 |
| `POST /api/preferences/feedback` | 프론트 명시 호출 | 선택 +1.0, 미선택 -0.3 |

**사전 필요 데이터**

| 항목 | 없으면 |
|------|-------|
| `users` 행 | 학습 불가 |
| `user_preference` 테이블 존재 | 첫 INSERT 실패 |
| `places.mood` 값 | mood NULL → 학습·반영 모두 스킵 |

**추천 시 반영 조건**

| 조건 | 효과 |
|------|------|
| `userId != null` + `place.mood != null` | personalizationMultiplier 0.7~2.0 적용 |
| `userId == null` 또는 학습 데이터 없음 | multiplier 1.0 (영향 없음) |

---

### 4. 틈새 추천 (기능 6)

**갭 감지 사전 조건 (GET /api/trips/{tripId}/gaps)**

| 항목 | 필수 | 없으면 |
|------|------|-------|
| `trip` 행 존재 | O | 404 |
| 같은 trip 안에 `plan` 2개 이상 | O | 갭 0건 |
| 두 plan의 시간 차이 **30분 이상** | O | 기준 미달 → 미포함 |
| 두 plan이 **같은 날짜** | O | 다른 날짜 갭은 감지 안 함 |
| `plan.endTime` | △ | NULL이면 startTime + 60분 자동 처리 |
| `plan.place.latitude/longitude` | △ | NULL이면 이동시간 0으로 처리 |
| `trip.transport_mode` | X | NULL이면 WALK 폴백 |

**추천 실행 사전 조건 (POST /api/trips/{tripId}/gaps/recommend/stream)**

| 항목 | 필수 | 없으면 |
|------|------|-------|
| `userId` | O | 개인화 미반영 |
| `beforePlanId`, `afterPlanId` | O | 위치·길목 계산 불가 |
| 두 plan이 같은 trip | O | 400 에러 |
| `beforePlaceLat/Lng` | O | Google 검색 불가 |
| `afterPlaceLat/Lng` | X | NULL이면 길목 보너스 비활성화 |

---

### 5. 이동수단 API

| 항목 | 필수 |
|------|------|
| `trip` 행 존재 | O |
| `trip.transport_mode` 컬럼 DDL 적용 | O |

---

## 7. 최소 테스트 데이터 SQL

> 아래 SQL만 있으면 모든 기능이 동작합니다.

```sql
-- 사용자
INSERT INTO users (id, email, password, nickname)
VALUES (1, 'test@test.com', 'pw', '테스터');

-- Trip (transportMode 포함)
INSERT INTO trip (id, user_id, title, start_date, end_date, transport_mode)
VALUES (100, 1, '서울 당일치기', CURRENT_DATE, CURRENT_DATE, 'TRANSIT');

-- 좌표·태그 채워진 Place (AI 분석 없이 바로 추천 가능)
INSERT INTO places (place_id, name, category, space, type, mood,
                    rating, user_ratings_total, latitude, longitude,
                    google_place_id, business_status)
VALUES
  (1001, '성수 카페', 'cafe', 'INDOOR', 'CAFE', 'HEALING',
         4.6, 1200, 37.5440, 127.0540, 'TEST_SEONGSU', 'OPERATIONAL'),
  (1002, '롯데월드타워', 'tourist_attraction', 'INDOOR', 'SIGHTS', 'TRENDY',
         4.7, 50000, 37.5126, 127.1025, 'TEST_LWT', 'OPERATIONAL');

-- Plan 2개 (같은 날, 2시간 텀 → 틈새 감지됨)
INSERT INTO plan (id, trip_id, user_id, place_id, title, start_time, end_time)
VALUES
  (10001, 100, 1, 1001, '브런치',
     CURRENT_DATE + TIME '11:00', CURRENT_DATE + TIME '13:00'),
  (10002, 100, 1, 1002, '전망대',
     CURRENT_DATE + TIME '15:00', CURRENT_DATE + TIME '17:00');

-- (선택) 개인화 학습 시드 — 없으면 multiplier 1.0 (무영향)
-- INSERT INTO user_preference (user_id, mood, score) VALUES (1, 'HEALING', 3.0);
```

**이 데이터로 동작하는 기능**

| 기능 | 동작 여부 |
|------|---------|
| 이동수단 GET/PATCH | ✅ |
| 틈새 감지 (GET /gaps) | ✅ 13:00~15:00 → 120분 텀 감지 |
| 틈새 추천 SSE | ✅ |
| 일반 추천 (/api/recommendations) | ✅ |
| 날씨 알림 스케줄러 | ✅ (당일 일정 있으면 자동 감지) |
| 개인화 반영 | ⚠️ 피드백 누적 후 효과 — `POST /api/preferences/feedback` 몇 번 호출하거나 일정 교체 필요 |

---

## 8. 결과 0건 시나리오 대응표

| 증상 | 원인 | 해결 |
|------|------|------|
| 갭 0건 | plan 2개 미만 / 다른 날짜 / 시간 차이 30분 미만 | 같은 날 30분 이상 텀 있는 plan 2개 필요 |
| 추천 0건 | 화이트리스트 매칭 0건 / Smart Expansion 한계 | 주거 밀집 지역 한계 — 반경 확장 자동 실행됨 |
| 알림 0건 | OpenWeather 키 미설정 / 모든 plan이 INDOOR / POP 70% 미만 | 키 확인, OUTDOOR 장소 포함한 plan 필요 |
| transportMode 항상 WALK | trip.transport_mode NULL | PATCH로 설정 또는 trip 생성 시 포함 |
| 개인화 안 먹힘 | user_preference 비어있음 / places.mood NULL / userId 요청 누락 | 피드백 API 호출 or userId 요청에 포함 |
| 첫 호출이 1~2분 걸림 | 신규 좌표라 ~20개 장소 최초 AI 분석 중 | 두 번째 호출부터 캐시 적용 → 수 초 내 응답 |
| 명소 아닌 것이 추천됨 | Google types가 generic한 장소 (establishment만 있음) | 사전 컷 4단계로 차단됨 — 여전히 보이면 화이트리스트에서 해당 type 제거 |

---

*작성일: 2026-05-04*
