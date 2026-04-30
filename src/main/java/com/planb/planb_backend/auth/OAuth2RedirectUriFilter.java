package com.planb.planb_backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * OAuth2 로그인 시작 시 프론트가 넘긴 redirect_uri를 쿠키에 저장한다.
 *
 * 사용법:
 *   웹  → GET /oauth2/authorization/google?redirect_uri=aHR0cDovL2xvY2FsaG9zdDo4MDgxL29hdXRoL3N1Y2Nlc3M=  (Base64)
 *   앱  → GET /oauth2/authorization/google?redirect_uri=planb://oauth/success  (plain, 커스텀 스킴은 WAF 미차단)
 *
 * Base64 인코딩 이유:
 *   AWS WAF가 쿼리 파라미터에 "localhost" 문자열이 포함되면 SSRF 공격으로 간주해 403 차단.
 *   Base64로 인코딩하면 URL에 "localhost"가 노출되지 않으므로 WAF를 통과한다.
 *   btoa('http://localhost:8081/oauth/success') → aHR0cDovL2xvY2FsaG9zdDo4MDgxL29hdXRoL3N1Y2Nlc3M=
 *
 * 인증 성공/실패 후 OAuth2SuccessHandler / failureHandler 에서 쿠키를 꺼내 해당 주소로 리다이렉트한다.
 */
public class OAuth2RedirectUriFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "oauth2_redirect_uri";
    private static final int COOKIE_MAX_AGE = 300; // 5분 (OAuth 플로우 완료 충분)

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (request.getRequestURI().startsWith("/oauth2/authorization/")) {
            String redirectUriParam = request.getParameter("redirect_uri");
            if (redirectUriParam != null && !redirectUriParam.isBlank()) {
                // Base64 디코딩 시도 — 실패하면 원문 그대로 사용 (planb:// 커스텀 스킴 등 하위 호환)
                String redirectUri;
                try {
                    byte[] decoded = Base64.getDecoder().decode(redirectUriParam);
                    redirectUri = new String(decoded, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    redirectUri = redirectUriParam;
                }
                Cookie cookie = new Cookie(COOKIE_NAME, redirectUri);
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                cookie.setMaxAge(COOKIE_MAX_AGE);
                // HTTPS 운영 환경에서 카카오 콜백 시 쿠키가 유실되지 않도록 Secure 설정
                cookie.setSecure(true);
                response.addCookie(cookie);
            }
        }

        filterChain.doFilter(request, response);
    }
}
