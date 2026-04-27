package com.planb.planb_backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * OAuth2 로그인 시작 시 프론트가 넘긴 redirect_uri를 쿠키에 저장한다.
 *
 * 사용법:
 *   웹  → GET /oauth2/authorization/google?redirect_uri=http://localhost:8082/oauth/success
 *   앱  → GET /oauth2/authorization/google?redirect_uri=planb://oauth/success
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
            String redirectUri = request.getParameter("redirect_uri");
            if (redirectUri != null && !redirectUri.isBlank()) {
                Cookie cookie = new Cookie(COOKIE_NAME, redirectUri);
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                cookie.setMaxAge(COOKIE_MAX_AGE);
                response.addCookie(cookie);
            }
        }

        filterChain.doFilter(request, response);
    }
}
