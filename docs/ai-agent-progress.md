# 🤖 Plan B AI Agent Server - Progress Tracker

> **목적**: 토큰 절약 및 AI 컨텍스트 유지를 위한 프로젝트 상태 요약 문서
> **마지막 업데이트**: 2026-05-29 — 프로젝트 초기화, 연쇄 복구 개발 시작

---

## 📌 현재 개발 목표 (Current Sprint)

**기능 2 — 연쇄 복구 (Cascade Recovery)** ← 핵심 차별점 (1순위)

### 목표 흐름
```
비 예보 감지
→ 해당 장소 실내 대안 탐색 (Google Places API)
→ 이동시간 재계산 (이동수단 반영)
→ 다음 장소 시작시간 자동 조정
→ 하루 전체 일정 재배치
→ SSE로 변경된 일정 스트리밍
→ "이렇게 바꿀까요?" 사용자 승인 한 번
```

### 엔드포인트
- **이 서버 단독**: `POST /recovery/stream`
- **Spring Boot 연결 후**: `POST /api/trips/{tripId}/days/{day}/recovery/stream`

### SSE 이벤트 스펙 (프론트 합의 완료)
| 이벤트 | 데이터 |
|--------|--------|
| `recovery_start` | `{ "message": "일정을 재조합하고 있어요..." }` |
| `place_changed` | `{ tripPlaceId, originalName, newName, reason }` |
| `time_adjusted` | `{ tripPlaceId, originalVisitTime, newVisitTime, reason }` |
| `recovery_done` | `{ day, places: [ ...전체 재조합 일정... ] }` |

---

## ✅ 완료된 작업 (Done)

- (없음 — 초기 상태)

---

## 🔄 진행 중인 작업 (In Progress)

- [ ] **프로젝트 초기 세팅**
  - FastAPI 앱 기본 구조 생성
  - `.env` / `requirements.txt` 작성
  - `main.py` 진입점 구성

- [ ] **연쇄 복구 Mock 구현**
  - `mock/sample_itinerary.json` — Spring Boot 연결 전 테스트용 Mock 일정
  - `POST /recovery/stream` — Mock 데이터 기반 SSE 스트리밍 동작 확인

---

## ⏳ 대기 중인 작업 (To-Do)

### Phase 1 — 단독 서버 구축 (Spring Boot 연결 전)
- [ ] `routers/recovery.py` — FastAPI 라우터 + SSE StreamingResponse
- [ ] `services/recovery_service.py` — 핵심 복구 오케스트레이터
- [ ] `services/weather_service.py` — 강수 확률 70% 이상 판단 (OpenWeatherMap)
- [ ] `services/place_service.py` — Google Places Nearby Search (실내 대안 탐색)
- [ ] `services/time_service.py` — 이동시간 재계산 + 연쇄 시프트 로직
- [ ] `models/request.py` / `models/response.py` — Pydantic 모델
- [ ] 단독 로컬 실행 테스트 완료 (`uvicorn main:app --reload`)

### Phase 2 — Spring Boot 연동
- [ ] Spring Boot `GET /api/trips/{tripId}/days/{day}` 호출로 일정 데이터 수신
- [ ] Spring Boot `PATCH /api/plans/{planId}/schedule` 호출로 승인 후 시간 반영
- [ ] `AiServerConfig.java` (Spring Boot 측) — WebClient Bean, timeout 설정
- [ ] `application.yml` (Spring Boot 측) — `ai-server.base-url` 환경변수 추가
- [ ] Spring Boot ↔ Python AI 서버 통합 테스트

---

## 🌐 API 엔드포인트 현황

| 메서드 | 경로 | 설명 | 상태 |
|--------|------|------|------|
| POST | `/recovery/stream` | 연쇄 복구 SSE 스트리밍 (단독) | ⏳ 미구현 |
| POST | `/api/trips/{tripId}/days/{day}/recovery/stream` | 연쇄 복구 (Spring Boot 연결 후 최종 경로) | ⏳ 미구현 |

---

## 🛠️ 환경 변수 상태

| 변수명 | 설명 | 상태 |
|--------|------|------|
| `OPENAI_API_KEY` | OpenAI GPT-4o 호출 | ⚠️ .env에 추가 필요 |
| `GOOGLE_MAPS_API_KEY` | Places Nearby Search (실내 대안 탐색) | ⚠️ .env에 추가 필요 |
| `WEATHER_API_KEY` | OpenWeatherMap 강수 확률 | ⚠️ .env에 추가 필요 |
| `SPRING_BACKEND_URL` | 메인 백엔드 호출 URL | ⚠️ 연동 시점에 추가 |

---

## 📋 Spring Boot 메인 백엔드 연동 체크리스트

> 배포 2일 전 연동 시 이 체크리스트 순서대로 진행

- [ ] Python AI 서버 ECR 이미지 빌드 + ECR Push
- [ ] ECS 태스크 정의 등록 (클라우드팀)
- [ ] `application.yml`에 `ai-server.base-url` 환경변수 추가 (Spring Boot)
- [ ] `AiServerConfig.java` WebClient Bean 추가 (Spring Boot)
- [ ] `RecoveryController.java` Spring Boot 측 프록시 엔드포인트 추가
- [ ] VPC 내부 통신 확인 (보안 그룹 인바운드 설정 — 클라우드팀)
- [ ] 통합 E2E 테스트

---

## 🔗 참고 자료

- 메인 백엔드 Dev API: `https://api-dev.planb-travel.cloud`
- Spring Boot places[] 응답 스펙: `category(String)`, `type(FOOD|CAFE|SIGHTS|SHOP|MARKET|THEME|CULTURE|PARK)`
- Spring Boot 이동수단 enum: `WALK | TRANSIT | CAR` (속도: WALK=0.08, TRANSIT=0.5, CAR=0.67 km/min)
- WeatherScheduler 참고: 강수 확률 70% 이상 → 실내 대안 탐색, 반경 8,000m(차량 20분)
