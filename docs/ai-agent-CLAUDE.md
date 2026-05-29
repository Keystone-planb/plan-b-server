# [AI 에이전트 페어 프로그래머: 업무 지시서] — Plan B AI Agent

> 이 파일은 Plan B AI 에이전트 서버의 개발 헌법입니다. Claude는 모든 응답 전 이 규칙을 복습하고,
> 철저히 'Python AI 서버 개발자'의 관점에서 답변합니다.

---

## 🎯 프로젝트 요약 및 나의 역할

- **프로젝트명**: Plan B AI Agent Server
- **내 역할**: Plan B 서비스의 **AI 에이전트 전담 개발자**.
  - Python FastAPI 서버를 독립적으로 개발하고,
  - Spring Boot 메인 백엔드와 HTTP로 연결한다.
  - 프론트엔드 코드는 작성하지 않으며, AI 로직과 API 설계에만 집중한다.
- **배포 전략**: 먼저 단독으로 구축 → 실제 배포 2일 전에 Spring Boot 메인 백엔드와 연결
- **통신 구조**:
  ```
  프론트엔드 → Spring Boot (메인 백엔드) → Python AI 서버 (이 프로젝트)
                                          ↑
                         VPC 내부 통신 (WebClient HTTP 호출)
  ```

---

## ⚙️ 기술 스택 (Tech Stack)

- **Language**: Python 3.11+
- **Framework**: FastAPI
- **AI**: OpenAI API (GPT-4o / GPT-4o-mini), LangChain
- **Streaming**: FastAPI `StreamingResponse` + SSE (Server-Sent Events)
- **HTTP 클라이언트**: `httpx` (Spring Boot 메인 백엔드 호출용)
- **환경변수**: `python-dotenv` + `.env` 파일
- **패키지 관리**: `pip` + `requirements.txt`

---

## 🔗 Spring Boot 메인 백엔드 연동 정보

> 아직 연결 전 단계. 로컬 개발 시에는 Mock 데이터로 대체한다.

| 항목 | 내용 |
|------|------|
| 메인 백엔드 Dev URL | `https://api-dev.planb-travel.cloud` |
| 내부 통신 방식 | HTTP REST (JSON) |
| 인증 방식 | 내부 서버 간 통신 — JWT 불필요, 서버 IP/VPC로 접근 제한 |
| Spring Boot가 제공하는 데이터 | Trip, Itinerary, TripPlace (일정 정보), Place 좌표·카테고리 |

**연쇄 복구(Cascade Recovery)에서 Spring Boot에서 받아올 데이터:**
```json
GET /api/trips/{tripId}/days/{day} 응답의 places[] 구조:
{
  "tripPlaceId": 1,
  "placeId": "ChIJ...",
  "name": "성산일출봉",
  "visitTime": "09:00",
  "endTime": "11:00",
  "latitude": 33.458,
  "longitude": 126.942,
  "category": "park",
  "type": "PARK",
  "transitGapMinutes": 30
}
```

---

## 🚨 핵심 규칙 (Core Rules)

1. **플랜 먼저**: 무작정 코딩부터 하지 말고, 항상 구조와 흐름을 먼저 제시하고 승인받은 뒤 코딩 시작.
2. **API 키는 반드시 `.env`에**: `OPENAI_API_KEY` 등 민감한 정보는 절대 코드에 하드코딩하지 않는다.
3. **Mock 우선 개발**: Spring Boot 연결 전까지는 Mock 데이터로 개발하여 독립적으로 테스트 가능하게 한다.
4. **SSE 스트리밍 표준 준수**: 프론트가 소비하는 이벤트 이름/구조는 아래 스펙에서 절대 변경하지 않는다.
5. **상태 동기화**: 기능 완료 시마다 `progress.md`를 직접 업데이트한다.
6. **수정은 바로**: "이거 바꿔줘" 요청 시 길게 설명하지 않고 즉시 코드 수정.

---

## 📦 프로젝트 구조 (목표)

```
planb-ai-agent/
├── .env                        # API 키 등 환경변수 (gitignore)
├── .env.example                # 환경변수 예시 (git에 포함)
├── requirements.txt
├── main.py                     # FastAPI 앱 진입점
├── progress.md                 # 개발 진행 상황 (항상 최신 유지)
├── CLAUDE.md                   # 이 파일
├── routers/
│   └── recovery.py             # POST /recovery/stream 라우터
├── services/
│   ├── recovery_service.py     # 연쇄 복구 핵심 로직
│   ├── weather_service.py      # 강수 확률 판단
│   ├── place_service.py        # Google Places API 연동 (실내 대안 탐색)
│   └── time_service.py         # 이동시간 재계산 로직
├── models/
│   ├── request.py              # Pydantic 요청 모델
│   └── response.py             # Pydantic 응답/SSE 이벤트 모델
└── mock/
    └── sample_itinerary.json   # Spring Boot 연결 전 Mock 일정 데이터
```

---

## 🌊 연쇄 복구 SSE 스트리밍 스펙 (프론트와 합의된 스펙)

> **절대 변경 금지** — 프론트엔드가 이 이벤트 이름으로 UI를 구성함

| 이벤트 | 데이터 | 타이밍 |
|--------|--------|--------|
| `recovery_start` | `{ "message": "일정을 재조합하고 있어요..." }` | 즉시 |
| `place_changed` | `{ "tripPlaceId": 1, "originalName": "성산일출봉", "newName": "제주도립미술관", "reason": "비 예보로 실내 대안 추천" }` | 장소 교체마다 |
| `time_adjusted` | `{ "tripPlaceId": 2, "originalVisitTime": "13:00", "newVisitTime": "13:20", "reason": "이전 장소 이동시간 반영" }` | 시간 조정마다 |
| `recovery_done` | `{ "day": 1, "places": [ ...전체 재조합 일정... ] }` | 마지막 |

**엔드포인트**: `POST /recovery/stream`
- Spring Boot 연결 후 최종 경로: `POST /api/trips/{tripId}/days/{day}/recovery/stream`
- 이 서버 단독으로는 `POST /recovery/stream` (tripId/day는 request body로 받음)

---

## 📂 개발 프로세스

"A 기능 만들어줘"라고 하면 다음 순서로 진행:

1. **흐름 설계**: 입력 → 처리 단계 → SSE 이벤트 출력 흐름 먼저 제시
2. **Mock 구현**: Spring Boot 없이 단독 실행 가능한 버전으로 먼저 구현
3. **실제 연동**: Spring Boot API 호출로 교체
4. **progress.md 업데이트**: 완료 즉시 반영
