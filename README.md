# 🛡️Plan B — 실시간 여정 복구 AI 에이전트

**2026 BuildersLeague Project (2026.03 ~ 2026.07)**

날씨 변화, 장소 폐쇄 등 여행 중 발생하는 돌발 상황에 대응하여 실시간으로 일정을 복구하고 최적의 대안을 제시하는 AI 기반 백엔드 시스템입니다.

---

## 🚀 Project Overview

여행 중 갑작스러운 비나 장소 휴무로 인해 계획이 어긋나는 경험에서 시작되었습니다.
Plan B는 사용자의 위치, 이동 수단, 취향 태그(공간·유형·분위기)를 분석하여 즉시 대안을 추천하고 빈 시간(Gap)을 채워주는 지능형 가이드입니다.

단순 CRUD를 넘어 SSE 스트리밍, 비동기 분석 파이프라인, PostgreSQL JSONB 최적화, Reactor Netty ConnectionPool 튜닝, AI 서버(Python FastAPI) 프록시 연동 등 백엔드 엔지니어링을 통해 사용자 경험을 극대화했습니다.

---

## 🛠 Tech Stack

### Backend & Infrastructure

| 분류 | 기술 |
| --- | --- |
| **Core** | Java 17 (Amazon Corretto), Spring Boot 3.x |
| **Data** | PostgreSQL (Supabase), JPA/Hibernate, HikariCP |
| **Cache / 분산락** | Redis (AWS ElastiCache Serverless), Redisson (RLock, TTL 캐시) |
| **Security** | Spring Security, JWT (HMAC-SHA512), OAuth2 (Google, Kakao) |
| **Async / Streaming** | Spring WebFlux (WebClient), SSE (SseEmitter), `@Async`, `@Scheduled` |
| **Infra** | AWS ECS Fargate, ECR, ALB (SSL/ACM), Docker |
| **CI/CD** | GitHub Actions (Rolling Deployment), Discord 빌드 알림 |

### External AI & Data API

| API | 용도 |
| --- | --- |
| **OpenAI GPT-4o-mini** | 장소 리뷰 AI 분석·요약, space/type/mood 태그 생성 |
| **Google Maps Places API** | 주변 장소 검색, 상세 정보·리뷰 수집 |
| **Naver Search API** | 국내 블로그 리뷰 수집 |
| **Open-Meteo** | 실시간 날씨 데이터 조회 |
| **Expo Push Notification** | 날씨 알림 앱 푸시 발송 |
| **Python FastAPI (AI 서버)** | 일정 동선 최적화 SSE 스트리밍 (내부 연동) |

---
<img width="1024" height="559" alt="image" src="https://github.com/user-attachments/assets/5202c8c9-82f6-4bf7-80d2-9e0b4a61aa88" />


## 🏗 System Architecture

<img width="1024" height="559" alt="image" src="https://github.com/user-attachments/assets/2aba8fca-df62-428a-b64a-6780a69a01e4" />


---

## 💡 Key Design Decisions

**Stateless Auth (JWT + Refresh Token)**
ECS Fargate 수평 확장 환경을 고려하여 세션 공유가 필요 없는 인증 구조 설계.
Access Token 1시간, Refresh Token 14일. Redis에 저장 후 재발급·블랙리스트 처리.

**SSE (Server-Sent Events) Streaming**
AI 분석 특성상 발생하는 대기 시간을 줄이기 위해, 분석 완료된 장소부터 즉시 프론트로 Push하여 체감 속도 개선.
`progress → place(×N) → done` 이벤트 흐름. 15초 heartbeat ping으로 iOS/ALB idle timeout 방지.

**Async Analysis Pipeline (전용 ThreadPool 3종 분리)**
리뷰 수집 + LLM 분석의 병목을 방지하기 위해 Executor를 분리 설계.

* `analysisExecutor` (core 3, max 5, DiscardPolicy) — 병렬 AI 분석
* `streamingExecutor` (core 2, max 3, AbortPolicy) — SSE 파이프라인
* `placeAutoAnalysisExecutor` (core 2, max 3, DiscardOldestPolicy) — 백그라운드 자동 분석

**WebClient ConnectionPool 튜닝 (Reactor Netty)**
외부 API(OpenAI·Google·Naver) 장기 운영 시 좀비 연결(ECONNRESET -104) 누적 방지.
각 클라이언트에 `ConnectionProvider(maxIdleTime=10s, evictInBackground=30s)` 독립 풀 적용.

**중복 추천 방지**
SSE 스트리밍 시 동일 여행(tripId)에 이미 등록된 Google Place ID를 수집하여 후보에서 제외.
AI 분석 이전 단계에서 필터링하여 불필요한 API 호출 차단.

---

## 📊 Data Modeling

```text
users
 ├── id, email (unique), nickname
 ├── provider (local / google / kakao)
 └── expo_push_token

trips
 ├── trip_id, title, start_date, end_date
 ├── transport_mode (WALK / TRANSIT / CAR)
 └── user_id (FK)
      └── itineraries (일차별 묶음)
           ├── day, date
           └── trip_places (개별 일정)
                ├── place_id (Google Place ID)
                ├── name, visit_time, end_time
                ├── visit_order, memo
                ├── source (NORMAL / SOS / WEATHER / GAP)
                └── [PLAN B 대체 시] name = "[장소명] (PLAN B)"

places  ← AI 분석 결과 캐시 (30일)
 ├── google_place_id (unique)
 ├── space (INDOOR / OUTDOOR / MIX)
 ├── type (CAFE / FOOD / CULTURE / MARKET ...)
 ├── mood (HEALING / TRENDY / LOCAL ...)
 └── review_data (JSONB)
      ├── totalSummary: "50자 이내 AI 종합 요약"
      └── platformSummaries: { Google: "...", Naver: "..." }

bookmarks
 ├── user_id (FK), google_place_id
 └── created_at

notifications
 ├── user_id, plan_id
 ├── type (WEATHER_ALERT)
 ├── precipitation_prob, alternative_place_ids (JSONB)
 └── is_read, push_sent_at

UserPreference
 └── mood별 선호 가중치 (피드백 누적)

```

**JSONB 컬럼 선택 이유**
플랫폼별 리뷰 요약 구조가 가변적 → 스키마 변경 없이 확장 가능.
PostgreSQL JSONB는 GIN 인덱스 지원으로 조회 성능 보장.

---

## 🔥 Technical Challenges & Solutions

### 1️⃣ SSE 스트리밍 한글 깨짐 및 충돌 해결

* **Issue:** `@PostMapping(produces = "text/event-stream")` + `response.setContentType()` 수동 호출 시 `SseEmitterReturnValueHandler`와 충돌 → 500 에러 + 한글 깨짐.
* **Cause:** Spring MVC는 `produces` 어노테이션으로 Content-Type을 결정한다. 수동 `setContentType()` 호출이 핸들러보다 먼저 실행되면 응답 초기화 충돌.
* **Solution:**
```java
// Before (500 발생)
@PostMapping("/recommendations/stream")
public SseEmitter stream(HttpServletResponse response) {
    response.setContentType("text/event-stream;charset=UTF-8");

// After (정상)
@PostMapping(value = "/recommendations/stream", produces = "text/event-stream;charset=UTF-8")
public SseEmitter stream(HttpServletResponse response) {
    response.setCharacterEncoding("UTF-8"); // charset만 유지

```


추가로 Tomcat의 ISO-8859-1 인코딩으로 한글 닉네임 깨짐 → 비ASCII 문자를 UTF-8 퍼센트 인코딩하는 `toAsciiSafeUrl()` 유틸 직접 구현.

### 2️⃣ 갭 감지 알고리즘 정렬 버그

* **Issue:** 일정 사이 빈 시간 계산 시 특정 조합 누락 → 틈새 추천 미작동.
* **Cause:** visitOrder(입력 순서) 기준 정렬 시, 역순 입력 → 음수 갭 발생.
* **Solution:**
```sql
-- Before: 추가 순서 기준
ORDER BY date ASC, visit_order ASC

-- After: 실제 방문 시간 기준
ORDER BY date ASC, visit_time ASC NULLS LAST

```



### 3️⃣ 최초 장소 조회 시 AI 데이터 Null 대응 (Lazy Analysis)

* **Issue:** 한 번도 분석하지 않은 장소 조회 시 space, type, mood, reviewSummary 전부 null.
* **Solution:** 최초 조회 시 `@Async`로 분석 자동 트리거 → 프론트는 `/analysis-status` 폴링(3초 간격)으로 PENDING → COMPLETE 감지.
```java
if (dbPlace.isEmpty()) {
    placeAnalysisService.triggerAnalysisAsync(placeId); // @Async — 즉시 반환
}
return toDetailDto(placeId, result, dbPlace); // 첫 조회는 null 허용

```



### 4️⃣ OAuth2 커스텀 스킴(planb://) 보안 정책 우회

* **Issue:** 로그인 성공 후 planb:// 앱 스킴 리다이렉트 시 StrictHttpFirewall이 //를 경로 조작 공격으로 차단.
* **Solution:**
```java
@Bean
public HttpFirewall allowDoubleSlashFirewall() {
    StrictHttpFirewall firewall = new StrictHttpFirewall();
    firewall.setAllowUrlEncodedDoubleSlash(true);
    return firewall;
}

```



### 5️⃣ ECONNRESET (-104) 좀비 연결 문제 해결

* **Issue:** 장기 운영 환경에서 OpenAI·Google·Naver WebClient가 Connection reset by peer 에러로 AI 분석 실패 및 SSE 스트리밍 멈춤.
* **Cause:** 기본 WebClient는 커넥션 풀 없이 최대 500개 연결 무제한 유지. 외부 서버(OpenAI)가 먼저 끊은 유휴 연결을 재사용 시도 → ECONNRESET.
* **Solution:** 세 클라이언트 모두 독립 ConnectionProvider 적용.
```java
private static final ConnectionProvider OPENAI_POOL = ConnectionProvider.builder("openai-pool")
        .maxConnections(10)
        .maxIdleTime(Duration.ofSeconds(10))    // 10초 유휴 연결 즉시 제거
        .evictInBackground(Duration.ofSeconds(30)) // 30초마다 만료 연결 정리
        .build();

// OpenAI에만 추가로 재시도 (ECONNRESET 발생 시 최대 2회)
.retryWhen(Retry.max(2).filter(e -> e.getMessage().contains("Connection reset")))

```



### 6️⃣ MARKET 카테고리 대안 추천 0개 버그

* **Issue:** MARKET 카테고리 요청 시 SSE가 place 이벤트 0개 → 100%에서 멈춤.
* **Cause:** Google Places type 매핑이 establishment(너무 광범위) → 공원·다리·자동차 매장까지 포함 → AI가 전부 다른 타입으로 분류 → AI 2차 검열에서 전량 탈락.
* **Solution:**
```java
// Before
case "MARKET" -> "establishment";   // 수만 개 장소 유형 포함

// After
case "MARKET" -> "grocery_or_supermarket";  // 마트·슈퍼마켓 정확 타겟

```



### 7️⃣ AI 프롬프트 정확도 개선

* **Issue:** 타입 구분 기준 없이 옵션만 나열 → FOOD/CAFE, SIGHTS/CULTURE 혼동 빈발. `reviews.toString()` 입력 형식으로 AI 파싱 정확도 저하.
* **Solution:** 3가지 개선 동시 적용.
* 입력 포맷 구조화: `reviews.toString()` → 플랫폼별 번호 목록 텍스트
* 타입 정의 명확화: 각 옵션에 설명 + 경계 케이스 구분 기준 추가 (FOOD vs CAFE, SIGHTS vs CULTURE 등)
* Chain of Thought: AI가 결론 전 reasoning 필드에 근거를 먼저 작성하게 유도


```json
// 응답 형식 (reasoning은 내부 사고용 — DB 저장 안 함)
{
  "reasoning": "리뷰에 '전시', '작품' 키워드 → CULTURE 판단",
  "space": "INDOOR",
  "type": "CULTURE",
  "mood": "CLASSIC",
  "review_data": "국내 최대 규모의 현대 미술 전시 공간",
  "summaries": { "Google": "...", "Naver": "..." }
}

```



### 8️⃣ 날씨 기반 알림 스케줄러

```text
@Scheduled(fixedRate = 4h)
├── Open-Meteo API로 24시간 이내 일정 장소별 날씨 조회
├── 강수확률 70% 이상 AND OUTDOOR 장소 필터링
├── 조건 충족 시 Redisson 분산락으로 중복 발송 방지
├── Notification 저장 → Expo Push API로 앱 푸시 발송
└── seed-test 엔드포인트로 배포 환경 실제 플로우 검증 가능

```

---

## 🔑 Key API Endpoints

### 인증 (Auth)

| Method | Path | 설명 |
| --- | --- | --- |
| **POST** | `/api/users/signup` | 회원가입 |
| **POST** | `/api/auth/login` | JWT 로그인 |
| **POST** | `/api/auth/refresh` | 토큰 재발급 |
| **POST** | `/api/auth/logout` | 로그아웃 |
| **GET** | `/oauth2/authorization/{provider}` | 소셜 로그인 (Google/Kakao) |
| **POST** | `/api/auth/email/request` | 이메일 인증 코드 발송 |
| **POST** | `/api/auth/email/verify` | 인증 코드 확인 |

### 장소 (Place)

| Method | Path | 설명 |
| --- | --- | --- |
| **GET** | `/api/places/search` | 장소명 검색 |
| **GET** | `/api/places/{placeId}` | 장소 상세 (AI 분석 자동 트리거) |
| **GET** | `/api/places/{placeId}/analysis-status` | AI 분석 완료 여부 폴링 |
| **GET** | `/api/places/{placeId}/summary` | AI 리뷰 요약 조회 |
| **POST** | `/api/places/{placeId}/reanalyze` | 장소 재분석 강제 실행 |

### 즐겨찾기 (Bookmark)

| Method | Path | 설명 |
| --- | --- | --- |
| **POST** | `/api/bookmarks` | 즐겨찾기 추가 |
| **GET** | `/api/bookmarks` | 즐겨찾기 목록 조회 |
| **DELETE** | `/api/bookmarks/{bookmarkId}` | 즐겨찾기 삭제 |

### 여행·일정 (Trip / Plan)

| Method | Path | 설명 |
| --- | --- | --- |
| **POST** | `/api/trips` | 여행 계획 생성 |
| **GET** | `/api/trips` | 내 여행 목록 |
| **GET** | `/api/trips/{id}` | 여행 상세 |
| **POST** | `/api/trips/{id}/days/{day}/locations` | 일정에 장소 추가 |
| **PATCH** | `/api/plans/{planId}/schedule` | 시간·메모 수정 |
| **PATCH** | `/api/plans/{planId}/order` | 방문 순서 변경 |
| **PATCH** | `/api/plans/{planId}/move` | 다른 일차로 이동 |
| **DELETE** | `/api/plans/{planId}` | 일정 장소 삭제 |

### 대안 추천 (Recommendation)

| Method | Path | 설명 |
| --- | --- | --- |
| **POST** | `/api/recommendations/stream` | SSE 스트리밍 대안 추천 ⭐ |
| **POST** | `/api/plans/{planId}/replace` | PLAN B 장소 대체 |

### 일정 최적화 (Optimize)

| Method | Path | 설명 |
| --- | --- | --- |
| **POST** | `/api/trips/{tripId}/days/{day}/places/{tripPlaceId}/optimize/stream` | AI 서버 동선 최적화 SSE 프록시 |
| **POST** | `/api/trips/{tripId}/days/{day}/places/{tripPlaceId}/optimize/confirm` | 대안 장소 선택 확정 |
| **POST** | `/api/trips/{id}/days/{day}/recovery/confirm` | 날씨 복구 확정 (일정 일괄 반영) |

### 틈새 추천 (Gap)

| Method | Path | 설명 |
| --- | --- | --- |
| **GET** | `/api/trips/{tripId}/gaps` | 일정 내 빈 시간 자동 감지 |
| **POST** | `/api/trips/{tripId}/gaps/recommend/stream` | 틈새 장소 SSE 추천 |

### 알림 (Notification)

| Method | Path | 설명 |
| --- | --- | --- |
| **GET** | `/api/notifications/{userId}` | 미확인 알림 조회 |
| **POST** | `/api/notifications/{id}/replace/{newPlaceId}` | 대안으로 일정 교체 |
| **POST** | `/api/notifications/{id}/dismiss` | 알림 닫기 |

### 시스템

| Method | Path | 설명 |
| --- | --- | --- |
| **GET** | `/actuator/health` | ECS 헬스체크 |
| **GET** | `/api/admin/stats` | 관리자 대시보드 통계 (ADMIN) |

---

## 👥 Contribution

| 이름 | 역할 | 주요 기여 |
| --- | --- | --- |
| **김태형** | Backend Lead | 프로젝트 전체 아키텍처 설계, AWS 인프라 구축, 전 기능 백엔드 구현 |

**백엔드 기여 상세 (김태형)**

* **인증 시스템:** JWT + Refresh Token + OAuth2 (Google·Kakao) 소셜 로그인
* **AI 파이프라인:** Google·Naver 리뷰 병렬 수집 → GPT-4o-mini 분석 → JSONB 캐싱 (30일)
* **추천 엔진:** 위치·이동수단·취향(space/type/mood) 기반 퍼널 필터링 + 스코어링
* **SSE 스트리밍:** 장소별 분석 완료 순 실시간 push + heartbeat + executor 분리
* **WebClient 튜닝:** ConnectionProvider 독립 풀로 ECONNRESET 근절
* **갭 감지 알고리즘:** 일정 간 빈 시간 자동 감지 + 이동 시간 보정
* **날씨 알림 스케줄러:** 4시간 주기 강수확률 체크 → Expo 푸시 발송
* **즐겨찾기:** 장소 저장·조회·삭제 전 기능
* **일정 최적화:** AI 서버(Python FastAPI) SSE 프록시 연동 + 확정 API
* **AWS 인프라:** ECS Fargate + ECR + ALB(SSL) + GitHub Actions 자동 배포

---

## 🔧 Collaboration Convention

### 1. Issue & Branch Strategy

| 구분 | 규칙 | 예시 |
| --- | --- | --- |
| **이슈 제목** | 타입: 작업 내용 | `feat: 지도 API 연동` |
| **브랜치명** | 타입/#이슈번호-내용 | `feat/#12-map-api` |

### 2. Commit Message Type

| 타입 | 설명 | 타입 | 설명 |
| --- | --- | --- | --- |
| **feat** | 새로운 기능 | **fix** | 버그 수정 |
| **docs** | 문서 수정 | **style** | 코드 포맷팅 |
| **refactor** | 리팩토링 | **chore** | 빌드/패키지 설정 |
| **perf** | 성능 개선 | **test** | 테스트 코드 |

### 3. Pull Request Workflow

* PR 본문에 `Closes #이슈번호` 기재로 이슈 자동 종료
* 최소 1명 이상 리뷰어 승인 후 `main` 머지

---

## 🚀 Local Setup

```bash
# 1. 환경변수 설정
cp .env.example .env
# .env에 API 키 입력
# jwt.secret, google.maps.api-key, openai.api-key,
# naver.client.id, naver.client.secret 등

# 2. 빌드 및 실행
./gradlew build -x test
java -jar build/libs/planb_backend-*.jar

```

---

© 2026 Plan B Team · BuildersLeague
