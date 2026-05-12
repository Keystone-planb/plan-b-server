# Plan B — 실시간 여정 복구 에이전트

> **2026 BuildersLeague Project (2026.03 ~ 2026.07)**
> 날씨·혼잡도 등 예상치 못한 상황에서 여행 일정을 실시간으로 복구하고 대안을 제시하는 AI 기반 여행 에이전트

---

## 프로젝트 핵심 요약

여행 중 갑작스러운 날씨 변화나 장소 폐쇄 같은 돌발 상황이 발생했을 때,
사용자의 여행 스타일(공간·유형·분위기 태그)과 현재 위치·이동 수단을 고려해
**즉시 대안 장소를 추천하고 일정을 재편성**하는 백엔드 시스템을 설계·구현했습니다.

단순 CRUD를 넘어 **SSE 스트리밍 AI 추천**, **비동기 리뷰 분석 파이프라인**,
**갭(빈 시간) 감지 알고리즘**, **날씨 기반 알림 스케줄러**를 직접 설계하고 운영 환경까지 배포했습니다.

---

## 기술 스택

### Backend

| 분류 | 기술 |
|------|------|
| Language | Java 17 (Amazon Corretto) |
| Framework | Spring Boot 3.x |
| ORM | Spring Data JPA / Hibernate 6 |
| Security | Spring Security, JWT (HMAC-SHA512), OAuth2 (Google·Kakao) |
| Async / Streaming | `@Async` + `ThreadPoolTaskExecutor`, SSE (`SseEmitter`) |
| Scheduler | `@Scheduled` (Spring Scheduling) |
| HTTP Client | WebClient (Spring WebFlux) |
| Build | Gradle (Groovy) |

### External API

| API | 용도 |
|-----|------|
| OpenAI GPT-4o | 장소 리뷰 AI 분석·요약, 대안 장소 추천 |
| Google Maps Places API | 장소 검색·상세 조회·리뷰 수집 |
| Naver Search API | 국내 블로그 리뷰 수집 |
| Open-Meteo | 실시간 날씨 데이터 조회 |

### Infrastructure

| 분류 | 기술 |
|------|------|
| Database | PostgreSQL (Supabase), JSONB 컬럼 활용 |
| Container | Docker, Amazon ECR |
| Compute | AWS ECS Fargate (Serverless 컨테이너) |
| Load Balancer | AWS ALB (SSL Termination — ACM 인증서) |
| CI/CD | GitHub Actions (빌드 → ECR Push → ECS 롤링 배포) |
| Connection Pool | HikariCP |

---

## 아키텍처

```
[React Native WebView App]
         │  HTTPS
         ▼
   [AWS ALB] ── SSL 인증서 (ACM)
         │
         ▼
[ECS Fargate] Spring Boot 3.x
    │        │        │
    │     [JWT Filter]│
    │     [OAuth2]    │
    │                 │
    ├── Google Maps API
    ├── Naver Search API
    ├── OpenAI API
    └── PostgreSQL (Supabase)

[GitHub Actions]
  push → Build JAR → Docker Build
       → ECR Push → ECS Rolling Deploy
       → Discord 알림 (성공/실패)
```

### 주요 설계 결정

**Stateless 인증 (JWT + Refresh Token)**
- Access Token: HMAC-SHA512, 1시간 유효
- Refresh Token: UUID, 14일 유효, DB 저장
- 이유: ECS Fargate 멀티 인스턴스 환경에서 세션 공유 없이 수평 확장 가능

**SSE 스트리밍 추천**
- 이유: AI 추천은 장소별로 분석 시간이 다름. 전체 완료 후 한 번에 응답하면 UX 불량
- 분석 완료된 장소부터 즉시 push → 프론트 스켈레톤 UI와 조합해 체감 속도 향상

**비동기 AI 분석 파이프라인**
- 이유: Google·Naver·Instagram 리뷰 수집 + OpenAI 분석은 최대 30초 소요
- `@Async("analysisExecutor")` + 전용 ThreadPool(core 7, max 14)로 비동기 처리
- 최초 장소 조회 시 자동 트리거, 두 번째 조회부터 분석 결과 반환

---

## 데이터 모델링

```
users
 ├── id, email (unique), nickname
 ├── provider (local / google / kakao)
 └── provider_id

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
                └── [PLAN B 대체 시] name = "[장소명] (PLAN B)"

places  ← AI 분석 결과 캐시
 ├── google_place_id (unique)
 ├── space (INDOOR / OUTDOOR / MIX)
 ├── type (CAFE / FOOD / CULTURE ...)
 ├── mood (HEALING / TRENDY / LOCAL ...)
 └── review_data (JSONB)
      ├── totalSummary: "..."
      └── platformSummaries: { Google: "...", Naver: "...", Instagram: "..." }

notifications
 ├── user_id, trip_place_id
 ├── type (WEATHER_ALERT)
 └── weather_summary, alternative_places (JSONB)
```

**JSONB 컬럼 선택 이유**
- 플랫폼별 리뷰 요약 구조가 가변적 → 스키마 변경 없이 확장 가능
- PostgreSQL JSONB는 GIN 인덱스 지원으로 조회 성능 보장

---

## 핵심 기술적 도전 및 해결

### 1. SSE 스트리밍 한글 깨짐 (AWS ELB 환경)

**문제**
`@PostMapping(produces = "text/event-stream;charset=UTF-8")`을 수동 `response.setContentType()`으로 교체했더니 `SseEmitterReturnValueHandler`와 충돌해 500 에러 발생.

**원인**
Spring MVC는 `produces` 어노테이션으로 Content-Type을 결정한다.
수동 `setContentType()` 호출이 핸들러보다 먼저 실행되면 응답 초기화 충돌.

**해결**
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

---

### 2. 갭 감지 알고리즘 정렬 버그

**문제**
일정 사이 빈 시간(갭)을 감지하는 로직에서 특정 조합의 일정이 누락됨.

**원인**
일정 정렬 쿼리가 `visitOrder`(추가 순서) 기준이었음.
사용자가 시간 역순으로 일정을 추가하면 정렬이 뒤집혀 음수 갭 발생 → 조건 미충족.

```
사용자 입력 순서:
  14:00~16:00 (visitOrder=1) → 09:00~11:00 (visitOrder=2)

정렬 결과 (visitOrder ASC):
  pair: 14:00 → 09:00 = gapMin -300분 → 갭으로 인정 안 됨
```

**해결**
```java
// Before
ORDER BY tp.itinerary.date ASC, tp.visitOrder ASC

// After
ORDER BY tp.itinerary.date ASC, tp.visitTime ASC NULLS LAST
```
visitTime(실제 방문 시간) 기준으로 정렬해 항상 시간 순 pair 보장.

---

### 3. 최초 장소 조회 시 AI 필드 null 문제

**문제**
장소 상세 조회 시 `space`, `type`, `mood`, `googleReview` 등 AI 분석 필드가 항상 null.

**원인**
AI 분석은 수동 트리거 엔드포인트를 통해서만 실행 가능했음.
한 번도 분석하지 않은 장소는 DB에 레코드 자체가 없어 모든 AI 필드 null 반환.

**해결**
최초 상세 조회 시 비동기 분석 자동 트리거. 첫 방문은 null 반환하되 백그라운드 분석 즉시 시작.
두 번째 조회부터 모든 AI 필드 정상 응답.

```java
Optional<Place> dbPlace = placeRepository.findByGooglePlaceId(placeId);

if (dbPlace.isEmpty()) {
    placeAnalysisService.triggerAnalysisAsync(placeId); // @Async
}

return toDetailDto(placeId, result, dbPlace);
```

프론트는 `GET /api/places/{placeId}/analysis-status` 폴링(3초 간격)으로
`PENDING → COMPLETE` 전환 감지 후 모달 표시.

---

### 4. OAuth2 커스텀 스킴(planb://) URL 인코딩 차단

**문제**
카카오·구글 로그인 성공 후 `planb://oauth/success` 앱 스킴으로 리다이렉트 시
Spring의 `StrictHttpFirewall`이 `//`를 경로 조작 공격으로 차단.

**해결**
```java
@Bean
public HttpFirewall allowDoubleSlashFirewall() {
    StrictHttpFirewall firewall = new StrictHttpFirewall();
    firewall.setAllowUrlEncodedDoubleSlash(true);
    return firewall;
}
```

추가로 Tomcat이 Location 헤더를 ISO-8859-1로 인코딩해 한글 닉네임이 깨지는 문제를
비ASCII 문자를 UTF-8 퍼센트 인코딩으로 사전 변환하는 `toAsciiSafeUrl()` 유틸로 해결.

---

### 5. 날씨 기반 알림 스케줄러

**설계**
```
@Scheduled(fixedRate = 4h)
├── Open-Meteo API로 일정 장소별 날씨 조회
├── 강수확률 70% 이상 AND OUTDOOR 장소 필터링
├── 조건 충족 시 GPT로 대안 장소 3개 추천 (실내 우선)
└── Notification 저장 → 프론트 푸시
```

날씨 조건·날짜 조건을 우회하는 `seed-test` 엔드포인트를 구현해
배포 환경에서 실제 알림 플로우를 검증.

---

## API 주요 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/auth/login` | 이메일 로그인 |
| `POST` | `/api/auth/refresh` | 토큰 재발급 |
| `GET` | `/oauth2/authorization/{provider}` | 소셜 로그인 시작 |
| `GET` | `/api/places/search` | 장소 검색 |
| `GET` | `/api/places/{placeId}` | 장소 상세 (AI 분석 자동 트리거) |
| `GET` | `/api/places/{placeId}/analysis-status` | AI 분석 완료 여부 폴링 |
| `GET` | `/api/places/{placeId}/summary` | AI 리뷰 요약 조회 |
| `POST` | `/api/recommendations/stream` | 대안 장소 SSE 스트리밍 추천 |
| `POST` | `/api/trips` | 여행 계획 생성 |
| `GET` | `/api/trips/{tripId}` | 여행 상세 조회 |
| `POST` | `/api/trips/{tripId}/days/{day}/locations` | 일정에 장소 추가 |
| `DELETE` | `/api/plans/{planId}` | 일정 장소 삭제 |
| `POST` | `/api/plans/{planId}/replace` | PLAN B 대체 |
| `PATCH` | `/api/plans/{planId}/schedule` | 시간·메모 수정 |
| `GET` | `/api/trips/{tripId}/gaps` | 빈 시간 갭 감지 |
| `POST` | `/api/trips/{tripId}/gaps/recommend/stream` | 갭 틈새 추천 SSE |
| `GET` | `/api/notifications/{userId}` | 날씨 알림 조회 |
| `GET` | `/actuator/health` | 헬스 체크 |

---

## 팀 정보 및 기여

| 이름 | 역할 | 주요 기여 |
|------|------|---------|
| 김태형 | Backend | Spring Boot 서버 전체 설계 및 구현, AWS 인프라 구축·운영, CI/CD 파이프라인, AI 분석 파이프라인, SSE 스트리밍, 보안 레이어 |
| 프론트엔드 팀 | Frontend | React Native WebView 앱, UI/UX 설계 |

### 백엔드 기여 상세 (김태형)

- **인증 시스템**: JWT + Refresh Token + OAuth2(Google·Kakao) 소셜 로그인 전체 구현
- **AI 파이프라인**: Google·Naver·Instagram 리뷰 병렬 수집 → OpenAI 분석 → JSONB 캐싱
- **추천 엔진**: 사용자 선호도(space/type/mood) + 위치 + 이동 수단 기반 스코어링
- **SSE 스트리밍**: 장소별 분석 완료 순 실시간 push (progress → place×N → done)
- **갭 감지 알고리즘**: 일정 간 빈 시간 자동 감지 + 이동 시간·안전 마진 보정
- **날씨 알림 스케줄러**: 4시간 주기 날씨 체크 → 조건부 대안 추천 알림
- **AWS 인프라**: ECS Fargate + ECR + ALB(SSL) 구성, GitHub Actions 자동 배포

---

## 로컬 실행

```bash
# 1. 환경변수 설정
cp .env.example .env
# .env에 API 키 입력 (jwt.secret, google.maps.api-key, openai.api-key 등)

# 2. 빌드 및 실행
./gradlew build -x test
java -jar build/libs/planb_backend-*.jar
```

---

> © 2026 Plan B Team · BuildersLeague
