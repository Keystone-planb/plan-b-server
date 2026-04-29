package com.planb.planb_backend.auth;

import com.planb.planb_backend.domain.user.dto.AuthResponse;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final UserRepository userRepository;

    @Value("${oauth2.success-redirect-uri}")
    private String defaultSuccessRedirectUri;

    @Value("${oauth2.failure-redirect-uri}")
    private String defaultFailureRedirectUri;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
            Long userId = (Long) oAuth2User.getAttributes().get("userId");
            log.info("[OAuth2 Success] userId={}", userId);

            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("소셜 로그인 유저를 찾을 수 없습니다."));

            AuthResponse authResponse = authService.issueTokens(user);
            log.info("[OAuth2 Success] 토큰 발급 완료: userId={}", userId);

            // 쿠키에서 redirect_uri 읽기 — 없으면 기본값 사용
            String targetUri = extractRedirectUriFromCookie(request).orElse(defaultSuccessRedirectUri);
            log.info("[OAuth2 Success] redirect 목적지: {}", targetUri);
            clearRedirectUriCookie(response);

            // 닉네임 한글 인코딩 처리
            String encodedNickname = URLEncoder.encode(
                    authResponse.getNickname() != null ? authResponse.getNickname() : "",
                    StandardCharsets.UTF_8
            );

            String redirectUrl = targetUri
                    + "?access_token=" + authResponse.getAccessToken()
                    + "&refresh_token=" + authResponse.getRefreshToken()
                    + "&user_id=" + authResponse.getUserId()
                    + "&nickname=" + encodedNickname;

            // Tomcat은 Location 헤더를 ISO-8859-1로 인코딩함
            // 비ASCII 문자(한글 등)가 1자라도 있으면 UnmappableCharacterException 발생 → WhiteLabel
            // try-catch 밖(Tomcat commit 시점)에서 터지므로, setHeader 전에 반드시 ASCII로 변환
            String safeRedirectUrl = toAsciiSafeUrl(redirectUrl);
            log.info("[OAuth2 Success] 최종 redirect URL 생성 완료");

            // 직접 302 응답 — planb:// 커스텀 스킴 호환
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", safeRedirectUrl);
            // flushBuffer() 제거 — AWS ELB 환경에서 IllegalStateException 유발 가능

        } catch (Exception e) {
            log.error("[OAuth2 Success] 처리 중 예외 발생: {}", e.getMessage(), e);

            // 예외 발생 시 WhiteLabel 대신 실패 redirect로 안전하게 처리
            String failureUrl = defaultFailureRedirectUri + "?error="
                    + URLEncoder.encode("login_error", StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", failureUrl);
        }
    }

    /**
     * URL 문자열의 모든 비ASCII 문자를 UTF-8 퍼센트 인코딩으로 변환
     * Tomcat은 Location 헤더를 ISO-8859-1로 인코딩하므로, 비ASCII 문자가 있으면
     * UnmappableCharacterException 발생 → WhiteLabel Error Page 원인
     */
    private static String toAsciiSafeUrl(String url) {
        if (url == null) return "";
        StringBuilder sb = new StringBuilder(url.length() * 2);
        for (char c : url.toCharArray()) {
            if (c > 127) {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    sb.append(String.format("%%%02X", b & 0xFF));
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
