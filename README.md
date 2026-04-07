# Keystone
> **2026 BuildersLeague Project (2026.03 ~ 2026.07)**
> 흩어진 여행 정보를 하나로, 원큐에!
> 사용자 맞춤형 여행 일정 큐레이션 서비스

---

## Project Overview
- **프로젝트명**: Keystone (키스톤)
- **진행 기간**: 2026.03 ~ 2026.07
- **소속**: 2026 BuildersLeague
- **주요 목표**: Google Maps, Naver, Instagram 등 파편화된 여행 데이터를 통합하여 신뢰도 높은 정보와 대안 일정을 제공합니다.

## Tech Stack
- **Framework**: React Native (Cross-Platform)
- **State Management**: React Query, Zustand
- **Styling**: Tailwind CSS / Styled-components
 

## Collaboration Convention

### 1. Issue & Branch Strategy
모든 작업은 Issue 생성 후 해당 번호를 딴 Branch에서 진행합니다.

| 구분 | 규칙 (Naming Convention) | 예시 |
| :--- | :--- | :--- |
| **이슈 제목** | `[타입] 작업 내용` | `[Feat] 지도 API 연동 및 마커 표시` |
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

## 🚀 Key Features (WIP)
- [ ] 다양한 플랫폼(Google, Naver, SNS) 데이터 통합 파이프라인 구축
- [ ] 실시간 위치 기반 여행지 추천 및 대안 일정 제시
- [ ] 사용자 맞춤형 여행 경로 최적화 알고리즘

---
© 2026 Keystone Team. Powered by BuildersLeague.
