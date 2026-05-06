# PLAN B — 프론트엔드 API 명세서

> **서버 주소**: `https://api-dev.planb-travel.cloud`
> **인증 방식**: JWT Bearer Token — 모든 🔒 API는 `Authorization: Bearer {access_token}` 헤더 필수
> **🆕 NEW** = 이번에 새로 추가된 API　　**🔄 UPDATED** = 기존 대비 변경된 API

---

## ⚠️ Breaking Change — `walk` → `transportMode` 교체

> **대상 API**: `POST /api/recommendations`, `POST /api/recommendations/stream`

기존의 `walk: boolean` 필드가 `transportMode: string` 으로 교체되었습니다.

```json
// 이전 (더 이상 동작하지 않음)
{ "walk": false }

// 이후 (이렇게 바꿔야 함)
{ "transportMode": "CAR" }
```

| 값 | 이동속도 | 설명 |
|----|---------|------|
| `"WALK"` | 80m/분 | 도보 |
| `"TRANSIT"` | 350m/분 | 대중교통 |
| `"CAR"` | 500m/분 | 차량 |

---

## 목차

1. [인증 (Auth)](#1-인증)
2. [사용자 (User)](#2-사용자)
3. [여행 계획 (Trip)](#3-여행-계획)
4. [대안 추천 (Recommendation)](#4-대안-추천)
5. [🆕 틈새 추천 (Gap)](#5-틈새-추천)
6. [날씨 알림 (Notification)](#6-날씨-알림)
7. [개인화 학습 (Preference)](#7-개인화-학습)
8. [장소 (Place)](#8-장소)
9. [공통 Enum 정의](#9-공통-enum-정의)

---

## 1. 인증

### POST /api/auth/email/request — 이메일 인증 코드 발송
```json
// Request
{ "email": "user@example.com" }

// Response 200
{ "message": "인증 코드가 발송되었습니다." }
```

### POST /api/auth/email/verify — 인증 코드 확인
```json
// Request
{ "email": "user@example.com", "code": "123456" }

// Response 200
{ "message": "이메일 인증이 완료되었습니다." }
```

### POST /api/auth/login — 로그인
```json
// Request
{ "email": "user@example.com", "password": "myPassword123!" }

// Response 200
{
  "success": true,
  "message": "로그인에 성공하였습니다.",
  "access_token": "eyJhbGci...",
  "refresh_token": "607bf5ba-...",
  "token_type": "Bearer",
  "user_id": 3,
  "nickname": "태형"
}
```

### POST /api/auth/refresh — Access Token 재발급
```json
// Request
{ "refreshToken": "607bf5ba-..." }

// Response 200
{
  "success": true,
  "message": "Access Token이 재발급되었습니다.",
  "access_token": "eyJhbGci...",
  "token_type": "Bearer",
  "user_id": 3,
  "nickname": "태형"
}
```

### POST /api/auth/logout — 로그아웃
```json
// Request
{ "refreshToken": "607bf5ba-..." }

// Response 200
{ "message": "로그아웃 되었습니다." }
```

---

## 2. 사용자

### POST /api/users/signup — 회원가입
> 이메일 인증 완료 후 호출

```json
// Request
{
  "email": "user@example.com",
  "password": "myPassword123!",
  "nickname": "태형"
}

// Response 201
{
  "success": true,
  "message": "회원가입이 완료되었습니다.",
  "user_id": 3
}
```

### GET /api/users/me 🔒 — 내 프로필 조회
```json
// Response 200
{
  "userId": 3,
  "email": "user@example.com",
  "nickname": "태형",
  "provider": "LOCAL"
}
```

### DELETE /api/users/me 🔒 — 회원 탈퇴
```json
// Response 200
{ "message": "회원 탈퇴가 완료되었습니다." }
```

---

## 3. 여행 계획

### POST /api/trips 🔒 — 여행 생성
```json
// Request
{
  "title": "서울 여행",
  "startDate": "2026-06-01",
  "endDate": "2026-06-03",
  "travelStyles": ["HEALING", "CULTURE"]
}

// Response 201
{
  "tripId": 10,
  "title": "서울 여행",
  "startDate": "2026-06-01",
  "endDate": "2026-06-03",
  "totalDays": 3
}
```

### GET /api/trips 🔒 — 내 여행 목록
> `?status=ALL` (기본값) | `UPCOMING` | `PAST`

```json
// Response 200
[
  {
    "tripId": 10,
    "title": "서울 여행",
    "startDate": "2026-06-01",
    "endDate": "2026-06-03",
    "status": "UPCOMING"
  }
]
```

### GET /api/trips/{id} 🔒 — 여행 상세 (일정 전체)
```json
// Response 200
{
  "tripId": 10,
  "title": "서울 여행",
  "startDate": "2026-06-01",
  "endDate": "2026-06-03",
  "itineraries": [
    {
      "day": 1,
      "date": "2026-06-01",
      "places": [
        {
          "tripPlaceId": 55,
          "placeId": "ChIJxyz",
          "name": "경복궁",
          "visitTime": "10:00",
          "endTime": "12:00",
          "memo": "사진 많이 찍기"
        }
      ]
    }
  ]
}
```

### PATCH /api/trips/{id} 🔒 — 여행 정보 수정
```json
// Request (변경할 필드만 전송)
{
  "title": "서울 힐링 여행",
  "startDate": "2026-06-02",
  "endDate": "2026-06-04",
  "travelStyles": ["HEALING"]
}
```

### DELETE /api/trips/{id} 🔒 — 여행 삭제
> Response 204 No Content

### POST /api/trips/{id}/days/{day}/locations 🔒 — 일정에 장소 추가
```json
// Request
{
  "place_id": "ChIJxyz789",
  "name": "경복궁",
  "visitTime": "10:00",
  "endTime": "12:00",
  "memo": "사진 많이 찍기"
}

// Response 201
{
  "tripPlaceId": 55,
  "placeId": "ChIJxyz789",
  "name": "경복궁",
  "day": 1,
  "visitTime": "10:00",
  "endTime": "12:00",
  "memo": "사진 많이 찍기"
}
```

### 🆕 GET /api/trips/{id}/transport-mode 🔒 — 이동수단 조회

```json
// Response 200
"WALK"   // "WALK" | "TRANSIT" | "CAR" | null
```

### 🆕 PATCH /api/trips/{id}/transport-mode?mode={값} 🔒 — 이동수단 설정

> 여행 전체 기본 이동수단을 설정합니다. 틈새 추천 및 대안 추천에서 자동 적용됩니다.

```
PATCH /api/trips/10/transport-mode?mode=TRANSIT

// Response 204 No Content
```

---

## 4. 대안 추천

> PLAN B의 핵심 기능. 현재 위치 기반으로 대안 장소를 AI 분석하여 추천합니다.

### 🔄 Request Body 공통 필드

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `tripId` | Long | 선택 | 같은 여행 내 중복 제외 + 다음 일정 자동 추적 |
| `currentPlanId` | Long | 선택 | 현재 취소된 일정 ID |
| `currentLat` | Double | **필수** | 현재 위도 |
| `currentLng` | Double | **필수** | 현재 경도 |
| `radiusMinute` | int | **필수** | 이동 허용 시간 (분) |
| ~~`walk`~~ | ~~boolean~~ | ~~필수~~ | **삭제됨** — `transportMode`로 교체 |
| `transportMode` | String | 선택 | `"WALK"` / `"TRANSIT"` / `"CAR"` — null이면 trip 설정값 자동 적용, trip도 없으면 WALK |
| `selectedSpace` | String | 선택 | `INDOOR` / `OUTDOOR` / `MIX` |
| `selectedType` | String | 선택 | 장소 타입 (Enum 참고) |
| `keepOriginalCategory` | boolean | 선택 | true=원본 카테고리 유지 (기본값: false) |
| `considerNextPlan` | boolean | 선택 | true=다음 목적지 방향 동선 최적화 (기본값: false) |
| `nextLat` | Double | 선택 | 다음 목적지 위도 (null이면 DB 자동 조회) |
| `nextLng` | Double | 선택 | 다음 목적지 경도 |

### Response Body — PlaceResult (추천 장소 1개)

| 필드 | 타입 | 설명 |
|------|------|------|
| `placeId` | Long | DB 내부 ID |
| `googlePlaceId` | String | Google Place ID |
| `name` | String | 장소명 |
| `category` | String | Google 원본 카테고리 |
| `space` | String | INDOOR / OUTDOOR / MIX |
| `type` | String | AI 분석 장소 타입 |
| `mood` | String | AI 분석 무드 |
| `rating` | Double | 구글 평점 (0~5) |
| `reviewCount` | Integer | 구글 리뷰 수 |
| `latitude` | Double | 위도 |
| `longitude` | Double | 경도 |
| `address` | String | 한국어 주소 |
| `reviewSummary` | String | AI 종합 한줄 요약 |
| `googleReview` | String | 구글 리뷰 요약 |
| `naverReview` | String | 네이버 블로그 요약 |
| `instaReview` | String | 인스타그램 요약 |
| `businessStatus` | String | `OPERATIONAL` / `CLOSED_TEMPORARILY` / `CLOSED_PERMANENTLY` |
| `openingHours` | String | 운영시간 JSON 문자열 |
| `phoneNumber` | String | 전화번호 |
| `website` | String | 공식 웹사이트 URL |
| `priceLevel` | Integer | 가격대 0~4 |
| `photoUrl` | String | 대표 사진 URL |
| `lastSyncedAt` | String | 마지막 AI 분석 시각 |

---

### 🔄 POST /api/recommendations 🔒 — 대안 장소 추천 (동기)

> 분석 완료 후 한 번에 최대 5개 반환. 평균 소요 시간 약 15~25초.

```json
// Request
{
  "tripId": 10,
  "currentLat": 37.5665,
  "currentLng": 126.9780,
  "radiusMinute": 15,
  "transportMode": "WALK",
  "keepOriginalCategory": true,
  "considerNextPlan": false
}

// Response 200
{
  "recommendations": [ /* PlaceResult 배열 (최대 5개) */ ],
  "totalCount": 5
}
```

---

### 🔄 POST /api/recommendations/stream 🔒 — 대안 장소 추천 (SSE 실시간 스트리밍)

> 분석 완료된 장소부터 1개씩 실시간 push. 스켈레톤 UI와 함께 사용 권장.
> **EventSource 사용 불가** — Authorization 헤더 전송이 안 되므로 **fetch API**로 구현해야 합니다.

**이벤트 흐름**
```
event:progress  → 연결 즉시 전송 (스켈레톤 UI 트리거)
event:place     → 분석 완료된 장소 1개씩 push (최대 5번)
event:done      → 전송 완료 신호 → 연결 종료
```

**프론트엔드 구현 예시**
```javascript
const response = await fetch('https://api-dev.planb-travel.cloud/api/recommendations/stream', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${accessToken}`,
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream'
  },
  body: JSON.stringify({
    currentLat: 37.5665,
    currentLng: 126.9780,
    radiusMinute: 15,
    transportMode: 'WALK',   // ← walk: false 에서 변경
    keepOriginalCategory: true,
    considerNextPlan: false
  })
});

const reader = response.body.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;

  const text = decoder.decode(value);
  const lines = text.split('\n');

  let eventName = '';
  for (const line of lines) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim();
    if (line.startsWith('data:')) {
      const data = line.slice(5).trim();
      if (eventName === 'progress') {
        // { "message": "AI가 주변 장소를 분석 중입니다...", "total": 5 }
        showSkeleton();
      } else if (eventName === 'place') {
        appendPlaceCard(JSON.parse(data)); // PlaceResult 객체
      } else if (eventName === 'done') {
        hideLoadingIndicator();
      }
    }
  }
}
```

**이벤트별 data 형식**

| 이벤트 | data 형식 | 설명 |
|--------|-----------|------|
| `progress` | `{"message": "...", "total": 5}` | 분석 시작 알림 |
| `place` | PlaceResult JSON | 분석 완료된 장소 1개 |
| `done` | `[DONE]` | 전체 완료 |

---

### POST /api/plans/{planId}/replace 🔒 — 일정 장소 대체 (PLAN B 적용)

> 취소된 일정의 장소를 새 장소로 교체. 이름에 `(PLAN B)` 표시 자동 추가.

```json
// Request
{
  "newGooglePlaceId": "ChIJxyz789",
  "newPlaceName": "덕수궁"
}

// Response 200
{
  "tripPlaceId": 55,
  "googlePlaceId": "ChIJxyz789",
  "name": "[덕수궁] (PLAN B)",
  "message": "일정이 PLAN B로 대체되었습니다."
}
```

### PATCH /api/plans/{planId}/schedule 🔒 — 일정 시간/메모 수정

> 장소는 유지한 채 방문 시간·메모만 수정. null 전송 시 기존 값 유지.

```json
// Request
{
  "visitTime": "14:00",
  "endTime": "16:00",
  "memo": "우산 챙기기"
}

// Response 200
{
  "tripPlaceId": 55,
  "name": "덕수궁",
  "visitTime": "14:00",
  "endTime": "16:00",
  "memo": "우산 챙기기"
}
```

---

## 5. 🆕 틈새 추천

> 일정과 일정 사이 비어있는 시간(갭)을 감지하고, 그 시간에 들를 수 있는 장소를 추천합니다.

### 갭이란?

같은 날 연속한 두 일정 사이에 **30분 이상** 비는 시간을 갭이라고 합니다.
이동 시간과 여유 시간(10분)을 제외한 **실제 활용 가능한 시간(availableMinutes)** 을 계산해서 알려줍니다.

```
갭 = A 일정 종료 ~ B 일정 시작 사이 시간
availableMinutes = 갭 - 이동예상시간(직선거리/이동속도) - 여유시간(10분)
```

---

### GET /api/trips/{tripId}/gaps 🔒 — 갭 목록 조회

```
GET /api/trips/10/gaps
GET /api/trips/10/gaps?mode=CAR   // 이동수단 직접 지정
```

**Query Parameter**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `mode` | String | 선택 | `WALK` / `TRANSIT` / `CAR` — 없으면 trip 설정값 자동 사용 |

```json
// Response 200
[
  {
    "beforePlanId": 11,
    "beforePlanTitle": "투썸플레이스 부평역점",
    "beforePlanEndTime": "2026-07-03T11:00:00",
    "beforePlaceLat": 37.4919,
    "beforePlaceLng": 126.7245,
    "afterPlanId": 12,
    "afterPlanTitle": "스타벅스 부평역점",
    "afterPlanStartTime": "2026-07-03T12:00:00",
    "afterPlaceLat": 37.4951,
    "afterPlaceLng": 126.7223,
    "gapMinutes": 60,
    "estimatedTravelMinutes": 5,
    "availableMinutes": 45,
    "transportMode": "WALK"
  }
]
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `beforePlanId` | Long | 이전 일정 ID |
| `beforePlanTitle` | String | 이전 일정 장소명 |
| `beforePlanEndTime` | LocalDateTime | 이전 일정 종료 시각 |
| `beforePlaceLat/Lng` | Double | 이전 일정 장소 좌표 (null 가능) |
| `afterPlanId` | Long | 다음 일정 ID |
| `afterPlanTitle` | String | 다음 일정 장소명 |
| `afterPlanStartTime` | LocalDateTime | 다음 일정 시작 시각 |
| `afterPlaceLat/Lng` | Double | 다음 일정 장소 좌표 (null 가능) |
| `gapMinutes` | int | 두 일정 사이 총 여유 시간 (분) |
| `estimatedTravelMinutes` | int | 직선거리 기준 이동 예상 시간 (분) |
| `availableMinutes` | int | 실제 활용 가능한 시간 (분) |
| `transportMode` | String | 계산에 사용된 이동수단 |

---

### POST /api/trips/{tripId}/gaps/recommend/stream 🔒 — 틈새 장소 추천 (SSE 스트리밍)

> 갭 사이에 들를 수 있는 장소를 실시간으로 추천합니다.
> 이전 일정 위치를 출발지로, 다음 일정 위치를 동선 최적화에 활용합니다.
> **EventSource 사용 불가** — fetch API로 구현해야 합니다.

**이벤트 흐름**
```
event:progress  → 분석 시작 알림
event:place     → 추천 장소 1개씩 (최대 5개)
event:done      → 완료
```

**Request Body**

```json
{
  "beforePlanId": 11,
  "afterPlanId": 12,
  "transportMode": "WALK",
  "radiusMinute": 10
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `beforePlanId` | Long | **필수** | 갭의 이전 일정 ID (gaps 목록의 `beforePlanId`) |
| `afterPlanId` | Long | **필수** | 갭의 다음 일정 ID (gaps 목록의 `afterPlanId`) |
| `transportMode` | String | 선택 | 이동수단 직접 지정. null이면 trip 설정값 자동 사용 |
| `radiusMinute` | int | 선택 | 검색 반경(분). null이면 가용시간의 1/3 자동 계산 (최소 5분) |

**자동 계산되는 값 (요청에 포함 불필요)**

| 항목 | 계산 방식 |
|------|----------|
| 출발 좌표 | 이전 일정(`beforePlanId`) 장소의 좌표 자동 조회 |
| 도착 방향 | 다음 일정(`afterPlanId`) 장소 좌표로 동선 최적화 자동 적용 |
| 영업시간 검사 시각 | 이전 일정 종료 + 이동시간의 절반 (실제 도착 예상 시각) |
| 검색 반경 | `radiusMinute` 없으면 `max(5, availableMinutes / 3)` 자동 산출 |

**Response — SSE 이벤트**

이벤트 형식은 대안 추천(`/recommendations/stream`)과 동일합니다.

| 이벤트 | data 형식 | 설명 |
|--------|-----------|------|
| `progress` | `{"message": "...", "total": 5}` | 분석 시작 |
| `place` | PlaceResult JSON | 추천 장소 1개 |
| `done` | `[DONE]` | 완료 |

**프론트엔드 구현 예시**

```javascript
// 1. 먼저 갭 목록 조회
const gaps = await fetch('/api/trips/10/gaps', {
  headers: { 'Authorization': `Bearer ${token}` }
}).then(r => r.json());

// 2. 첫 번째 갭으로 추천 요청
const gap = gaps[0];
const response = await fetch(`/api/trips/10/gaps/recommend/stream`, {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream'
  },
  body: JSON.stringify({
    beforePlanId: gap.beforePlanId,
    afterPlanId: gap.afterPlanId
    // transportMode, radiusMinute 생략 시 자동 계산
  })
});

const reader = response.body.getReader();
const decoder = new TextDecoder();
let eventName = '';

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  const lines = decoder.decode(value).split('\n');
  for (const line of lines) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim();
    if (line.startsWith('data:')) {
      const data = line.slice(5).trim();
      if (eventName === 'place') appendGapCard(JSON.parse(data));
      if (eventName === 'done') hideLoading();
    }
  }
}
```

---

## 6. 날씨 알림

> 날씨 스케줄러가 4시간마다 자동 실행되어 강수 확률 70% 이상인 야외 일정에 알림을 생성합니다.

### GET /api/notifications/{userId} 🔒 — 미확인 알림 목록 조회

```json
// Response 200
[
  {
    "id": 12,
    "planId": 55,
    "type": "WEATHER_ALERT",
    "title": "내일 오후 일정에 비가 예보되어 있어요",
    "body": "덕수궁 방문 예정인 오후 2시에 강수 확률이 80%입니다. PLAN B를 확인해보세요.",
    "precipitationProb": 80,
    "createdAt": "2026-05-04T10:00:00",
    "alternatives": [
      {
        "placeId": 147,
        "name": "웨스틴 조선 서울",
        "category": "establishment",
        "rating": 4.5,
        "address": "대한민국 서울특별시 중구 소공로 106",
        "photoUrl": "https://maps.googleapis.com/..."
      }
    ]
  }
]
```

### POST /api/notifications/{notificationId}/replace/{newPlaceId} 🔒 — 대안으로 일정 교체

> 교체 + 알림 읽음 처리 + 개인화 학습 피드백이 한 번에 처리됩니다.

```
// Response 200
"일정이 성공적으로 교체되었습니다."
```

### POST /api/notifications/{notificationId}/dismiss 🔒 — 알림 닫기

> Response 204 No Content

---

## 7. 개인화 학습

> 사용자가 추천 결과에서 어떤 장소를 선택했는지 학습하여 이후 추천에 반영합니다.

### POST /api/preferences/feedback — 피드백 보고

```json
// Request
{
  "userId": 3,
  "shownPlaceIds": [147, 144, 160, 153, 148],
  "selectedPlaceId": 160
}

// Response 204 No Content
```

### GET /api/preferences/{userId}/summary — 취향 요약 조회

```json
// Response 200
{
  "userId": 3,
  "hasEnoughData": true,
  "message": "힐링 무드의 장소를 선호합니다."
}
```

---

## 8. 장소

### GET /api/places/search?query={검색어} — 장소 검색

```
GET /api/places/search?query=광화문 스타벅스
```

### GET /api/places/{placeId} — 장소 상세 정보

```json
// Response 200
{
  "placeId": 147,
  "googlePlaceId": "ChIJOU6v...",
  "name": "웨스틴 조선 서울",
  "address": "서울특별시 중구 소공로 106",
  "rating": 4.5,
  "reviewCount": 6484,
  "photoUrl": "https://..."
}
```

### GET /api/places/{placeId}/summary — 장소 AI 요약

```json
// Response 200
{
  "placeId": 147,
  "reviewSummary": "전통과 현대가 조화로운 5성급 호텔입니다.",
  "googleReview": "직원 친절도와 고급스러운 서비스가 돋보입니다.",
  "naverReview": "뷔페와 호캉스에 대한 긍정적인 후기가 많습니다.",
  "instaReview": "데이터 부족으로 분석 불가"
}
```

### GET /api/places/{placeId}/freshness — 분석 최신성 확인

```json
// Response 200
{
  "placeId": 147,
  "lastSyncedAt": "2026-05-04T02:28:04",
  "isFresh": true
}
```

---

## 9. 공통 Enum 정의

### 🆕 TransportMode (이동수단)
| 값 | 이동속도 | 설명 |
|----|---------|------|
| `WALK` | 80m/분 | 도보 |
| `TRANSIT` | 350m/분 | 대중교통 |
| `CAR` | 500m/분 | 차량 |

### Mood (여행 스타일 / 장소 분위기)
| 값 | 설명 |
|----|------|
| `HEALING` | 힐링 |
| `ADVENTURE` | 모험 |
| `ROMANTIC` | 로맨틱 |
| `FAMILY` | 가족 |
| `CULTURE` | 문화 |
| `FOOD` | 음식 |
| `NATURE` | 자연 |
| `URBAN` | 도시 |
| `CLASSIC` | 클래식 |
| `TRENDY` | 트렌디 |
| `LOCAL` | 현지 |

### PlaceType (장소 타입)
| 값 | 설명 |
|----|------|
| `FOOD` | 음식점 |
| `CAFE` | 카페 |
| `SIGHTS` | 관광명소 |
| `SHOP` | 쇼핑 |
| `MARKET` | 시장 |
| `THEME` | 테마시설 |
| `CULTURE` | 문화시설 |
| `PARK` | 공원 |

### Space (실내/외 구분)
| 값 | 설명 |
|----|------|
| `INDOOR` | 실내 |
| `OUTDOOR` | 야외 |
| `MIX` | 복합 |

---

## 에러 코드

| HTTP | 상황 |
|------|------|
| `400` | 요청 데이터 오류 (필수 필드 누락, 잘못된 값) |
| `401` | 토큰 없음 또는 만료 |
| `403` | 타인의 리소스 접근 시도 |
| `404` | 존재하지 않는 리소스 |
| `500` | 서버 오류 (AI 분석 실패 등) |

---

*최종 업데이트: 2026-05-05*
