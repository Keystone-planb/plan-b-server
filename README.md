# 🛡️ Plan B — 실시간 여정 복구 AI 에이전트

> **2026 BuildersLeague Project (2026.03 ~ 2026.07)**  
> 날씨 변화, 장소 폐쇄 등 여행 중 발생하는 돌발 상황에 대응하여 실시간으로 일정을 복구하고 최적의 대안을 제시하는 AI 기반 백엔드 시스템입니다.

---

## 🚀 Project Overview

여행 중 갑작스러운 비나 장소 휴무로 인해 계획이 어긋나는 경험에서 시작되었습니다.  
**Plan B**는 사용자의 위치, 이동 수단, 취향 태그(공간·유형·분위기)를 분석하여 **즉시 대안을 추천하고 빈 시간(Gap)을 채워주는 지능형 가이드**입니다.

단순 CRUD를 넘어 **SSE 스트리밍**, **비동기 분석 파이프라인**, **PostgreSQL JSONB 최적화** 등 백엔드 엔지니어링을 통해 사용자 경험을 극대화했습니다.

---

## 🛠 Tech Stack

### Backend & Infrastructure
- **Core**: Java 17 (Amazon Corretto), Spring Boot 3.x
- **Data**: PostgreSQL (Supabase), JPA/Hibernate, HikariCP
- **Security**: Spring Security, JWT (HMAC-SHA512), OAuth2 (Google, Kakao)
- **Async/Streaming**: Spring WebFlux (WebClient), SSE (SseEmitter), `@Async`, `@Scheduled`
- **Infra**: AWS ECS Fargate, ECR, ALB (SSL/ACM), Docker
- **CI/CD**: GitHub Actions (Rolling Deployment)

### External AI & Data API
- **AI**: OpenAI GPT-4o (리뷰 분석 및 대안 추천)
- **Search & Map**: Google Maps Places API, Naver Search API (블로그 리뷰)
- **Weather**: Open-Meteo (Real-time Weather)

---

## 🏗 System Architecture

<img width="1024" height="559" alt="image" src="https://github.com/user-attachments/assets/16d8e873-df64-4df0-b4f4-9bc3a259578f" />

### 💡 Key Design Decisions

1. **Stateless Auth (JWT + Refresh Token)**: ECS Fargate의 수평 확장(Auto-scaling) 환경을 고려하여 세션 공유가 필요 없는 인증 구조를 설계했습니다.
2. **SSE (Server-Sent Events) Streaming**: AI 분석 특성상 발생하는 대기 시간을 줄이기 위해, 분석이 완료된 장소부터 즉시 프론트로 Push하여 체감 속도를 개선했습니다.
3. **Async Analysis Pipeline**: 리뷰 수집 및 LLM 분석의 병목을 방지하기 위해 전용 ThreadPool 기반의 비동기 파이프라인을 구축했습니다.

---

## 📊 Data Modeling



- **JSONB 활용**: 플랫폼별(Google, Naver 등) 가변적인 리뷰 데이터 구조를 유연하게 수용하고, GIN 인덱스를 통해 조회 성능을 확보했습니다.
- **캐싱 전략**: `places` 테이블에 AI 분석 결과를 캐싱하여 반복적인 API 호출 비용을 절감했습니다.

---

## 🔥 Technical Challenges & Solutions

### 1️⃣ SSE 스트리밍 한글 깨짐 및 충돌 해결
- **Issue**: AWS ALB 환경에서 `produces = "text/event-stream"` 설정 시 한글 깨짐 및 500 에러 발생.
- **Cause**: 수동 `setContentType` 호출이 Spring의 `SseEmitterReturnValueHandler`와 충돌함.
- **Solution**: `produces` 어노테이션으로 응답 타입을 명시하고, 내부에서는 `setCharacterEncoding("UTF-8")`만 유지하여 핸들러와의 충돌을 방지했습니다.

### 2️⃣ 갭 감지 알고리즘 정렬 버그
- **Issue**: 일정 사이의 빈 시간을 계산할 때 특정 일정이 누락되는 현상.
- **Cause**: 단순 입력 순서(`visitOrder`) 정렬 시, 사용자가 시간을 역순으로 입력하면 음수 갭이 발생함.
- **Solution**: 쿼리 수준에서 `visitTime ASC NULLS LAST` 정렬을 강제하여 실제 시간 흐름에 따른 정확한 Pair를 보장했습니다.

### 3️⃣ 최초 조회 시 AI 데이터 Null 대응 (Lazy Analysis)
- **Issue**: 분석되지 않은 장소 조회 시 데이터 누락.
- **Solution**: 상세 조회 시 `@Async`로 분석 로직을 자동 트리거하고, 프론트에서 3초 간격 폴링을 통해 상태(`PENDING` -> `COMPLETE`)를 감지하여 자연스러운 UX를 구현했습니다.

### 4️⃣ 커스텀 스킴(planb://) 보안 정책 우회
- **Issue**: Spring `StrictHttpFirewall`이 앱 스킴의 `//`를 경로 조작 공격으로 판단하여 차단.
- **Solution**: `HttpFirewall` 빈 설정을 통해 안전하게 이중 슬래시를 허용하고, 비ASCII(한글 닉네임) 리다이렉트 시 UTF-8 퍼센트 인코딩 유틸을 직접 구현하여 데이터 깨짐을 방지했습니다.

---

## 🔑 Key API Endpoints

| Category | Method | Path | Description |
| :--- | :--- | :--- | :--- |
| **Auth** | `POST` | `/api/auth/login` | JWT 로그인 및 토큰 발급 |
| **Place** | `GET` | `/api/places/{placeId}` | 장소 상세 및 **AI 분석 자동 트리거** |
| **Recommend** | `POST` | `/api/recommendations/stream` | **SSE 기반** 실시간 대안 추천 |
| **Trip** | `GET` | `/api/trips/{tripId}/gaps` | 일정 내 **빈 시간 자동 감지** |
| **System** | `GET` | `/actuator/health` | ECS 헬스체크 및 모니터링 |

---

## 👥 Team & Contribution (Backend Lead)

**김태형 (Backend Engineer)**
- 프로젝트 전체 아키텍처 설계 및 인프라(AWS) 구축
- JWT/OAuth2 보안 레이어 및 CI/CD 파이프라인 구현
- AI 분석 파이프라인 및 SSE 스트리밍 로직 개발
- 갭 감지 알고리즘 및 날씨 알림 스케줄러 설계

---

## Collaboration Convention

### 1. Issue & Branch Strategy
모든 작업은 Issue 생성 후 해당 번호를 딴 Branch에서 진행합니다.

| 구분 | 규칙 (Naming Convention) | 예시 |
| :--- | :--- | :--- |
| **이슈 제목** | `타입: 작업 내용` | `feat: 지도 API 연동 및 마커 표시` |
| **브랜치명** | `타입/#이슈번호-내용` | `feat/#12-map-api` |

### 2. Commit Message Type
| 타입 | 설명 | 타입 | 설명 |
| :--- | :--- | :--- | :--- |
| **`feat`** | 새로운 기능 추가 | **`fix`** | 버그 수정 |
| **`docs`** | 문서 수정 | **`style`** | 코드 포맷팅 (로직 변경 X) |
| **`refactor`**| 코드 리팩토링 | **`chore`** | 빌드/패키지 설정 변경 |
| **`design`** | UI/UX 디자인 수정 | **`test`** | 테스트 코드 추가 |


### 3. Pull Request Workflow
- PR 생성 시 본문에 `Closes #이슈번호`를 기재하여 이슈를 자동 종료합니다.
- 최소 1명 이상의 리뷰어 승인 후 `main` 브랜치에 Merge 합니다.

---
© 2026 Keystone Team. Powered by BuildersLeague.
