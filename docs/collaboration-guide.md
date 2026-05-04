# PLAN B 백엔드 코드 협업 주의사항

> 이 문서는 외부 팀원이 본 레포지토리를 참고하거나 활용할 때 반드시 지켜야 할 규칙입니다.

---

## 절대 하면 안 되는 것

### 1. main 브랜치에 직접 push 금지
- `main` 브랜치는 배포 서버와 직접 연결되어 있습니다.
- main에 직접 push하면 **운영 서버가 즉시 영향을 받습니다.**
- 반드시 별도 브랜치를 만들어서 작업하고, PR(Pull Request)을 통해서만 반영 요청하세요.

```bash
# 올바른 작업 방법
git checkout -b feature/ai-your-feature-name
# 작업 후
git push origin feature/ai-your-feature-name
# 그 다음 GitHub에서 Pull Request 생성
```

### 2. 기존 파일 무단 수정 금지
아래 파일/폴더는 **현재 운영 중인 기능**입니다. 내용을 변경하거나 삭제하면 서비스 전체에 영향을 줍니다.

| 경로 | 설명 |
|------|------|
| `src/main/java/com/planb/planb_backend/domain/` | 전체 비즈니스 로직 |
| `src/main/java/com/planb/planb_backend/config/` | 보안·DB·외부 API 설정 |
| `src/main/java/com/planb/planb_backend/auth/` | 로그인·인증 처리 |
| `src/main/java/com/planb/planb_backend/jwt/` | JWT 토큰 발급·검증 |
| `src/main/resources/application.yml` | 서버 전체 설정 파일 |
| `build.gradle` | 의존성 관리 파일 |

### 3. 환경변수·API 키 절대 커밋 금지
- `application-local.yml`, `.env` 등 실제 키가 담긴 파일은 `.gitignore`에 포함되어 있습니다.
- 실수로 커밋되면 DB 비밀번호, JWT 시크릿, API 키가 외부에 노출됩니다.
- **이 파일들은 팀장에게 따로 요청하세요. 절대 코드에 직접 입력하지 마세요.**

### 4. DB 스키마(Entity) 무단 변경 금지
- `domain/**/entity/` 폴더의 파일을 수정하면 **DB 테이블 구조가 바뀌어** 운영 데이터가 손상될 수 있습니다.
- 새로운 테이블이나 필드가 필요하면 반드시 백엔드 담당자(태형)에게 먼저 논의하세요.

---

## 작업할 때 지켜야 할 것

### 브랜치 네이밍 규칙
```
feature/ai-{기능명}     # 새 기능
fix/ai-{버그명}         # 버그 수정
```

예시: `feature/ai-recommend-logic`, `fix/ai-score-calculation`

### 새 코드는 별도 패키지에 작성
기존 패키지를 건드리지 말고, 새 패키지를 만들어서 작업하세요.

```
# 권장 패키지 위치
src/main/java/com/planb/planb_backend/domain/ai/
```

### 기존 코드를 참고만 하고 복사·수정은 본인 서비스에서
- `RecommendationService`, `ScoringStrategy` 등의 로직은 **참고용**입니다.
- 동일한 로직이 필요하면 본인 서비스에 별도로 구현하세요.
- 원본 파일을 직접 수정하지 마세요.

---

## 서버 관련 주의사항

### 클론해도 서버에 접속되지 않습니다
- 코드 클론은 소스코드만 가져오는 것입니다.
- 실제 DB(Supabase), AWS 서버, Redis에 접근하려면 별도 인증이 필요합니다.
- 로컬에서 실행하려면 `application-local.yml` 파일이 별도로 필요합니다. (백엔드 담당자에게 요청)

### API 테스트는 개발 서버로
- 개발 서버 주소: `https://api-dev.planb-travel.cloud`
- 운영 서버 주소는 별도 공지 전까지 사용 금지

---

## 문의

코드 수정이 필요하거나 새로운 API가 필요하면 직접 수정하지 말고 **백엔드 담당자(태형)에게 먼저 요청**해주세요.
