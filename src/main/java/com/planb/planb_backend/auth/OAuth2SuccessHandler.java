package com.planb.planb_backend.auth;

import com.planb.planb_backend.domain.user.dto.AuthResponse;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final UserRepository userRepository;

    @Value("${oauth2.success-redirect-uri}")
    private String defaultSuccessRedirectUri;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Long userId = (Long) oAuth2User.getAttributes().get("userId");

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("소셜 로그인 유저를 찾을 수 없습니다."));

        AuthResponse authResponse = authService.issueTokens(user);

        // 프론트가 넘긴 redirect_uri 쿠키 → 없으면 기본값 사용
        String targetUri = extractRedirectUriFromCookie(request).orElse(defaultSuccessRedirectUri);
        clearRedirectUriCookie(response);

        String redirectUrl = UriComponentsBuilder.fromUriString(targetUri)
                .queryParam("access_token", authResponse.getAccessToken())
                .queryParam("refresh_token", authResponse.getRefreshToken())
                .queryParam("user_id", authResponse.getUserId())
                .queryParam("nickname", authResponse.getNickname())
                .build().toUriString();

        // sendRedirect() 대신 직접 302 응답 — planb:// 같은 커스텀 스킴도 안전하게 처리
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", redirectUrl);
        response.flushBuffer();
    }

    private java.util.Optional<String> extractRedirectUriFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return java.util.Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> OAuth2RedirectUriFilter.COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private void clearRedirectUriCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(OAuth2RedirectUriFilter.COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
