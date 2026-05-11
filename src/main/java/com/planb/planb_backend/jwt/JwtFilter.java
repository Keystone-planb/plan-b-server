package com.planb.planb_backend.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    // 토큰 검사가 필요 없는 공개 경로
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/users/signup",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/email/request",
            "/api/auth/email/verify"
    );

    /**
     * 공개 경로는 JwtFilter를 아예 건너뜀
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean skip = PUBLIC_PATHS.contains(path)
                || path.startsWith("/oauth2/")
                || path.startsWith("/login/oauth2/")
                || path.startsWith("/actuator/")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs")
                || path.equals("/swagger-ui.html");
        log.debug("[JWT] shouldNotFilter - path: {}, skip: {}", path, skip);
        return skip;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        // Bearer 토큰이 없으면 인증 없이 진행 — 보호된 엔드포인트는 EntryPoint가 401 처리
        if (!StringUtils.hasText(token)) {
            log.debug("[JWT] Authorization 헤더 없음 - uri: {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String email = jwtProvider.getEmailFromToken(token);
            String role  = jwtProvider.getRoleFromToken(token);

            // role 클레임 → ROLE_USER / ROLE_ADMIN 형태로 GrantedAuthority 적재
            List<GrantedAuthority> authorities = StringUtils.hasText(role)
                    ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    : List.of();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("[JWT] 인증 성공 - email: {}, role: {}, uri: {}", email, role, request.getRequestURI());

        } catch (ExpiredJwtException e) {
            log.warn("[JWT] 만료된 토큰 - uri: {}", request.getRequestURI());
            sendUnauthorized(response, "만료된 토큰입니다. 다시 로그인해 주세요.");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] 유효하지 않은 토큰 - uri: {}, cause: {}", request.getRequestURI(), e.getMessage());
            sendUnauthorized(response, "유효하지 않은 토큰입니다.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
