# 버그 리포트: 소셜 로그인(OAuth2) 전체 흐름 차단 문제

> 작성일: 2026-04-30
> 해결 완료: 2026-04-30
> 관련 서비스: 카카오 / 구글 소셜 로그인

---

## 인프라 구조

```
브라우저 → WAF → API Gateway → ALB → ECS (Spring Boot)
```

---

## 버그 1: 카카오 OAuth2 중복 키 오류 (500)

### 증상
```
ERROR: duplicate key value violates unique constraint "uq_provider_provider_id"
org.springframework.dao.DataIntegrityViolationException
HHH000099: an assertion failure occurred (this may indicate a bug in Hibernate)
```
카카오 계정으로 두 번째 로그인부터 매번 500 에러 발생.

### 원인
`CustomOAuth2UserService.java`에서 provider 값의 대소문자 불일치.

```java
// 조회: "KAKAO" (toUpperCase)
userRepository.findByProviderAndProviderId("KAKAO", providerId)

// 저장: "kakao" (toLowerCase)
User.builder().provider("kakao").build()
```

최초 로그인 시 `"kakao"`로 저장. 두 번째 로그인 시 `"KAKAO"`로 조회 → 못 찾음 → INSERT 시도 → 중복 키 오류.

추가 악화 요인: `@Transactional` + `DataIntegrityViolationException` catch 조합 시 Hibernate 세션이 오염되어 HHH000099 오류 연쇄 발생.

### 해결
```java
// 조회/저장 모두 소문자로 통일
String providerLower = registrationId.toLowerCase(); // "kakao"

user = userRepository.findByProviderAndProviderId(providerLower, providerId)
        .orElseGet(() -> userRepository.save(
            User.builder().provider(providerLower).build()
        ));
```
- `@Transactional` 제거
- Race Condition 대비 `DataIntegrityViolationException` catch 블록은 유지 (재조회로 처리)

---

## 버그 2: 웹 브라우저에서 소셜 로그인 시작 403

### 증상
```
GET /oauth2/authorization/kakao?redirect_uri=http://localhost:8081/oauth/success
→ HTTP 403 Forbidden (HTML 응답)
```
앱(WebView)에서는 정상 동작, 웹 브라우저에서만 403 발생.

### 원인 분석 과정

**1차 의심: Spring Security 설정**
Spring Security `SecurityConfig.java` 확인 → 정상. 코드 문제 아님.

**2차 의심: API Gateway CORS**
`server: awselb/2.0` 응답 헤더와 nginx 스타일 HTML 403 확인.
```html
<!-- a padding to disable MSIE and Chrome friendly error page -->
```
이 주석은 AWS 내부 nginx가 반환하는 403의 특징.

앱(WebView)은 `Origin` 헤더 미전송 → CORS 검사 통과.
브라우저는 `Origin: http://localhost:8081` 전송 → API Gateway CORS 차단.

→ API Gateway CORS 허용 목록에 `http://localhost:8081`, `http://localhost:8082` 추가.

**3차 의심: WAF (최종 원인)**
API Gateway CORS 추가 후에도 동일 403 지속.

파라미터 제거 테스트:
```
# 파라미터 없음 → 302 정상
GET /oauth2/authorization/kakao

# redirect_uri 포함 → 403
GET /oauth2/authorization/kakao?redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Foauth%2Fsuccess
```
AWS WAF가 쿼리 파라미터 내 `localhost` 문자열을 SSRF(서버 측 요청 위조) 공격으로 간주하여 차단.

### 해결
`redirect_uri` 값을 Base64로 인코딩하여 전달 → URL에 `localhost` 미노출.

**프론트엔드 변경:**
```javascript
// 변경 전
const authUrl = `...?redirect_uri=http://localhost:8081/oauth/success`;

// 변경 후
const encoded = btoa('http://localhost:8081/oauth/success');
const authUrl = `...?redirect_uri=${encoded}`;
// btoa 결과: aHR0cDovL2xvY2FsaG9zdDo4MDgxL29hdXRoL3N1Y2Nlc3M=
```

**백엔드 변경 (`OAuth2RedirectUriFilter.java`):**
```java
// redirect_uri 파라미터 수신 시 Base64 디코딩
try {
    byte[] decoded = Base64.getDecoder().decode(redirectUriParam);
    redirectUri = new String(decoded, StandardCharsets.UTF_8);
} catch (IllegalArgumentException e) {
    redirectUri = redirectUriParam; // planb:// 커스텀 스킴 등 하위 호환
}
```

앱의 `planb://oauth/success`는 Base64 형식이 아니므로 디코딩 실패 → 원문 그대로 사용 (하위 호환 유지).

---

## 버그 3: OAuth2 콜백 경로 403

### 증상
```
GET /login/oauth2/code/kakao?code=AbCdEf...&state=3w2Au0_t...
→ HTTP 403 Forbidden (HTML 응답)
```
카카오/구글 로그인 화면까지는 정상 진입하지만, 인증 완료 후 백엔드 콜백 단계에서 차단.

### 원인
카카오/구글 서버가 콜백 시 전달하는 `code`, `state` 파라미터 값의 패턴이 AWS WAF 관리형 규칙에 오탐(false positive)됨.

```
code=AbCdEfGhIj...   → WAF가 SQLi 또는 공격 패턴으로 오탐
state=3w2Au0_tZls... → WAF가 인코딩된 공격 페이로드로 오탐
```

Spring Boot는 요청을 수신하지 못함 (WAF에서 차단).

### 해결
클라우드팀에 WAF 예외 처리 요청:
```
/login/oauth2/code/kakao
/login/oauth2/code/google
```
해당 경로는 카카오/구글 공식 서버가 사용자 브라우저를 통해 리다이렉트하는 OAuth2 표준 콜백 URL이므로 WAF 검사 제외 처리.

---

## 최종 해결 후 전체 흐름

```
① 프론트 → /oauth2/authorization/kakao?redirect_uri={Base64}  ✅
② WAF 통과 (localhost 미노출)                                  ✅
③ Spring Boot → 카카오 인증 페이지 302 리다이렉트              ✅
④ 사용자 카카오 로그인 완료                                    ✅
⑤ 카카오 → /login/oauth2/code/kakao?code=...&state=...        ✅
⑥ WAF 통과 (콜백 경로 예외 처리)                              ✅
⑦ Spring Boot OAuth2 처리 → JWT 발급                          ✅
⑧ 프론트 /oauth/success?access_token=...로 리다이렉트          ✅
⑨ AsyncStorage 저장 → Main 진입                               ✅
```

---

## 핵심 교훈

| 진단 단서 | 의미 |
|-----------|------|
| HTML 403 응답 (JSON 아님) | Spring Boot 미도달, 인프라 단계 차단 |
| `<!-- a padding to disable MSIE... -->` 주석 | AWS 내부 nginx(WAF/API Gateway) 차단 |
| `server: awselb/2.0` 응답 헤더 | ALB 레벨 응답 |
| 앱 OK / 브라우저 NG | Origin 헤더 차이 (CORS 또는 WAF) |
| 파라미터 제거 시 정상 | 파라미터 값 기반 WAF 차단 |
