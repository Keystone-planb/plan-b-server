# PLAN B — 대안 추천 API 명세서

> **작성일**: 2026-05-01
> **서버 주소**: `https://api-dev.planb-travel.cloud`
> **인증 방식**: JWT Bearer Token (로그인 후 발급된 accessToken 사용)

---

## 목차

1. [공통 규칙](#1-공통-규칙)
2. [Enum 값 정의](#2-enum-값-정의)
3. [대안 장소 추천 API](#3-대안-장소-추천-api)
4. [일정 장소 대체 API](#4-일정-장소-대체-api)
5. [장소 심층 분석 트리거 API](#5-장소-심층-분석-트리거-api)
6. [추천 파이프라인 흐름](#6-추천-파이프라인-흐름)
7. [스코어링 알고리즘](#7-스코어링-알고리즘)
8. [외부 API 의존성](#8-외부-api-의존성)

---

## 1. 공통 규칙

### 인증 헤더
모든 API는 로그인 후 발급된 JWT accessToken이 필요합니다.

```
Authorization: Bearer {accessToken}
Content-Type: application/json
```

### 공통 에러 응답

| HTTP 상태 코드 | 의미 |
|--------------|------|
| 400 | 요청 값 오류 (필수 필드 누락, 잘못된 형식) |
| 401 | 인증 토큰 없음 또는 만료 |
| 403 | 권한 없음 (본인 리소스가 아님) |
| 404 | 리소스 없음 |
| 500 | 서버 내부 오류 |

---

## 2. Enum 값 정의

응답 데이터에서 사용되는 고정 값 목록입니다.

### Mood (장소 분위기)

| 값 | 설명 |
|----|------|
| `HEALING` | 힐링되는 조용한 분위기 |
| `ACTIVE` | 활동적이고 역동적인 분위기 |
| `TRENDY` | 트렌디하고 감각적인 분위기 |
| `CLASSIC` | 클래식하고 전통적인 분위기 |
| `LOCAL` | 현지 분위기의 로컬 감성 |

### Space (공간 유형)

| 값 | 설명 |
|----|------|
| `INDOOR` | 실내 |
| `OUTDOOR` | 실외 |
| `MIX` | 실내·외 혼합 |

### PlaceType (장소 카테고리)

| 값 | 설명 |
|----|------|
| `FOOD` | 음식점 |
| `CAFE` | 카페·디저트 |
| `SIGHTS` | 관광지·명소 |
| `PARK` | 공원·자연 |
| `MARKET` | 시장·상점가 |
| `SHOP` | 쇼핑몰·편집숍 |
| `THEME` | 테마파크·체험 |
| `CULTURE` | 미술관·박물관·문화시설 |

### BusinessStatus (영업 상태)

| 값 | 설명 |
|----|------|
| `OPERATIONAL` | 정상 영업 중 |
| `CLOSED_TEMPORARILY` | 임시 휴업 |
| `CLOSED_PERMANENTLY` | 영구 폐업 |

---

## 3. 대안 장소 추천 API

현재 위치와 이동 조건을 기반으로 AI가 분석한 대안 장소를 최대 5개 추천합니다.

```
POST /api/recommendations
```

### Request Body

```json
{
  "tripId": 1,
  "currentPlanId": 10,
  "currentLat": 37.5665,
  "currentLng": 126.9780,
  "radiusMinute": 20,
  "walk": false,
  "selectedSpace": "INDOOR",
  "selectedType": "CAFE",
  "keepOriginalCategory": false,
  "considerNextPlan": true,
  "nextLat": null,
  "nextLng": null
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `tripId` | Long | 권장 | 여행 ID — 같은 여행 내 중복 장소 제외 및 다음 일정 자동 추적에 사용 |
| `currentPlanId` | Long | 선택 | 현재 일정 ID — `keepOriginalCategory=true`일 때 원본 카테고리 조회용 |
| `currentLat` | Double | **필수** | 현재 위치 위도 |
| `currentLng` | Double | **필수** | 현재 위치 경도 |
| `radiusMinute` | int | **필수** | 이동 허용 시간(분) — 도보 시 80m/분, 차량 시 400m/분으로 반경 계산 |
| `walk` | boolean | **필수** | `true`=도보, `false`=차량 |
| `selectedSpace` | String | 선택 | 원하는 공간 유형 — `INDOOR` / `OUTDOOR` / `MIX` |
| `selectedType` | String | 선택 | 원하는 장소 카테고리 — PlaceType 값 참고 |
| `keepOriginalCategory` | boolean | 선택 | `true`이면 원본 구글 카테고리 유지, AI 2차 검열 스킵 (기본값: `false`) |
| `considerNextPlan` | boolean | 선택 | `true`이면 다음 목적지 방향 길목 장소에 보너스 점수 부여 (기본값: `false`) |
| `nextLat` | Double | 선택 | 다음 목적지 위도 — `null`이면 DB에서 자동 조회 |
| `nextLng` | Double | 선택 | 다음 목적지 경도 — `null`이면 DB에서 자동 조회 |

### Response Body

```json
{
  "recommendations": [
    {
      "placeId": 201,
      "googlePlaceId": "ChIJxyz789",
      "name": "경복궁 카페",
      "category": "cafe",
      "space": "INDOOR",
      "type": "CAFE",
      "mood": "HEALING",
      "rating": 4.5,
      "reviewCount": 1200,
      "latitude": 37.5796,
      "longitude": 126.9770,
      "address": "서울 종로구 사직로 161",
      "reviewSummary": "조용하고 아늑한 분위기로 힐링하기 좋은 카페",
      "googleReview": "구글 리뷰 기반 요약 텍스트",
      "naverReview": "네이버 블로그 기반 요약 텍스트",
      "instaReview": "인스타그램 기반 요약 텍스트",
      "businessStatus": "OPERATIONAL",
      "openingHours": "{\"periods\":[...], \"weekdayText\":[\"월요일: 09:00~21:00\",...]}",
      "phoneNumber": "02-1234-5678",
      "website": "https://example.com",
      "priceLevel": 2,
      "photoUrl": "https://maps.googleapis.com/maps/api/place/photo?...",
      "lastSyncedAt": "2026-04-30T12:00:00"
    }
  ],
  "totalCount": 5
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `placeId` | Long | DB 내부 PK |
| `googlePlaceId` | String | Google Place ID |
| `name` | String | 장소명 |
| `category` | String | Google 원본 카테고리 (소문자, 영문) |
| `space` | String | AI 분석 공간 유형 — Space Enum 참고 |
| `type` | String | AI 분석 카테고리 — PlaceType Enum 참고 |
| `mood` | String | AI 분석 분위기 — Mood Enum 참고 |
| `rating` | Double | 구글 평점 (0.0 ~ 5.0) |
| `reviewCount` | Integer | 구글 리뷰 수 |
| `latitude` | Double | 위도 |
| `longitude` | Double | 경도 |
| `address` | String | 한국어 주소 (Google formatted_address 기준) |
| `reviewSummary` | String | AI가 전체 플랫폼 리뷰를 종합한 한 줄 요약 (null 가능 — 미분석 장소) |
| `googleReview` | String | 구글 리뷰 기반 요약 (null 가능) |
| `naverReview` | String | 네이버 블로그 기반 요약 (null 가능) |
| `instaReview` | String | 인스타그램 기반 요약 (null 가능) |
| `businessStatus` | String | 영업 상태 — BusinessStatus Enum 참고 |
| `openingHours` | String | 영업시간 JSON 문자열 (null 가능) |
| `phoneNumber` | String | 전화번호 (null 가능) |
| `website` | String | 웹사이트 URL (null 가능) |
| `priceLevel` | Integer | 가격대 0(무료)~4(매우 비쌈) (null 가능) |
| `photoUrl` | String | 대표 사진 URL (null 가능) |
| `lastSyncedAt` | LocalDateTime | 마지막 AI 분석 시각 (null이면 미분석) |

> **주의**: `reviewSummary`, `googleReview`, `naverReview`, `instaReview`, `space`, `type`, `mood`는 AI 분석이 완료된 장소에만 값이 있습니다. 분석 전 장소는 `null`로 반환됩니다.

### 요청 예시 — 카페 추천 (차량 20분, 실내)

```json
{
  "tripId": 3,
  "currentLat": 37.5665,
  "currentLng": 126.9780,
  "radiusMinute": 20,
  "walk": false,
  "selectedSpace": "INDOOR",
  "selectedType": "CAFE",
  "considerNextPlan": false
}
```

### 요청 예시 — 관광지 추천 (도보 15분, 다음 일정 방향 고려)

```json
{
  "tripId": 3,
  "currentLat": 37.5665,
  "currentLng": 126.9780,
  "radiusMinute": 15,
  "walk": true,
  "selectedType": "SIGHTS",
  "considerNextPlan": true
}
```

---

## 4. 일정 장소 대체 API

특정 일정의 장소를 새로운 장소로 교체합니다. 교체 후 이름에 `(PLAN B)` 표시가 추가됩니다.

```
POST /api/plans/{planId}/replace
```

### Path Parameter

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `planId` | Long | 교체할 일정 ID (TripPlace ID) |

### Request Body

```json
{
  "newGooglePlaceId": "ChIJxyz789",
  "newPlaceName": "경복궁 카페"
}
```

### Response Body (200 OK)

```json
{
  "tripPlaceId": 10,
  "googlePlaceId": "ChIJxyz789",
  "name": "[경복궁 카페] (PLAN B)",
  "message": "일정이 PLAN B로 대체되었습니다."
}
```

---

## 5. 장소 심층 분석 트리거 API

DB에 저장된 특정 장소의 구글·네이버·인스타그램 리뷰를 수집하고 OpenAI 분석을 실행합니다. 분석 결과는 DB에 저장되며, 이후 추천 API 응답에 `reviewSummary`, `mood`, `space`, `type` 값이 채워집니다.

```
POST /api/places/{placeId}/analyze
```

### Path Parameter

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `placeId` | Long | 분석할 장소의 DB PK (`placeId` 필드 값) |

### Response (200 OK)

```
"성공적으로 분석하여 DB에 반영했습니다! (ID: 201)"
```

> **참고**: 분석은 외부 API(Naver, Instagram, OpenAI) 호출을 포함하므로 응답까지 약 15~30초 소요됩니다. 이미 7일 이내 분석된 장소는 캐시에서 즉시 반환됩니다.

---

## 6. 추천 파이프라인 흐름

추천 API 호출 시 내부적으로 아래 순서로 처리됩니다.

```
[1] 다음 일정 자동 추적 (considerNextPlan=true인 경우)
        ↓
[2] 검색 반경 계산
    - 도보: radiusMinute × 80m
    - 차량: radiusMinute × 400m
        ↓
[3] Google Nearby Search — 반경 내 장소 최대 20개 수집
        ↓
[4] DB Upsert — 신규 장소는 저장, 기존 장소는 정보 갱신
    (같은 여행에 이미 등록된 장소는 이 단계에서 제외)
        ↓
[5] 1차 퍼널 필터링 → 상위 7개 선발
    Hard Filter: 영업 중(OPERATIONAL) + 리뷰 10개↑ + 좌표 있음
    1차 스코어: 평점×리뷰수, 거리 페널티, 혼잡도 페널티
        ↓
[6] 병렬 심층 분석 (최대 45초, 7개 동시 처리)
    - 구글·네이버·인스타 리뷰 수집
    - OpenAI GPT-4o-mini로 요약 및 mood/space/type 태깅
    - 7일 이내 분석 이력 있으면 캐시 사용 (외부 API 호출 생략)
        ↓
[6.5] AI 2차 검열 (keepOriginalCategory=false인 경우)
    AI가 분석한 type이 요청한 selectedType과 다르면 제외
        ↓
[7] 최종 스코어링 → 상위 5개 반환
    (Haversine 거리 + 타원 동선 보너스 + Mood 개인화 가중치 포함)
```

---

## 7. 스코어링 알고리즘

최종 추천 순위를 결정하는 점수 계산 공식입니다.

### 기초 품질 점수

```
baseScore = 평점 × log10(리뷰수 + 1)
```

### 필터 가중치

| 조건 | 가중치 |
|------|--------|
| Space 일치 | × 2.0 |
| Space 불일치 | × 0.2 |
| PlaceType 일치 | × 1.5 |

### 거리 페널티

```
- 반경 내: 페널티 없음
- 반경 초과: max(0.1, 1.0 - (초과비율))
  → 최대 90% 감점
```

### 타원 동선 보너스 (considerNextPlan=true)

```
우회율 = (현재위치→장소 거리 + 장소→다음목적지 거리) / (현재위치→다음목적지 직선거리)
우회율 < 1.2 이면 × 2.5 (길목에 있는 장소 우대)
```

### Mood 개인화 가중치 (누적 피드백 기반)

```
가중치 = 1.0 + 누적점수 × 0.15
범위: 최소 0.7 ~ 최대 2.0 (클램프 적용)
피드백 없으면 1.0 (중립)
```

> 개인화 가중치는 사용자가 추천 결과에서 장소를 선택/미선택할 때마다 누적됩니다.
> 선택한 장소 Mood: +1.0 / 노출됐지만 미선택 Mood: -0.3

---

## 8. 외부 API 의존성

추천 서비스가 내부적으로 사용하는 외부 API 목록입니다.

| API | 용도 | 과금 방식 |
|-----|------|---------|
| **Google Maps Places API** | 장소 검색, 상세정보(주소·영업시간·전화번호), 리뷰, 사진 | 사용량 기반 유료 |
| **OpenAI GPT-4o-mini** | 리뷰 종합 요약, mood·space·type 자동 태깅 | 토큰 기반 유료 |
| **Naver Search API (블로그)** | 국내 블로그 리뷰 수집 | 무료 (일 25,000건) |
| **Instagram (RapidAPI)** | 인스타그램 게시물 기반 리뷰 수집 | 사용량 기반 유료 |

> 각 API 키는 서버 환경변수로 관리되며 외부 공유가 불가합니다. 동일한 외부 API를 활용하려면 별도로 키를 발급받아야 합니다.
