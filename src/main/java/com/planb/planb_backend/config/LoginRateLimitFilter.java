package com.planb.planb_backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 엔드포인트 Rate Limiting 필터
 *
 * 대상: POST /api/auth/login, POST /api/auth/email/request
 * 기준: IP 주소 (X-Forwarded-For → RemoteAddr 순 폴백)
 * 제한: 1분에 10회 초과 시 429 Too Many Requests 반환
 *
 * 구현: ConcurrentHashMap 기반 인메모리 카운터
 * - 외부 의존성 없음 (Redis 등 불필요)
 * - 만료된 IP 항목은 요청 시 자동 정리 (메모리 누수 방지)
 */
@Slf4j
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final int    MAX_REQUESTS  = 10;       // 허용 횟수
    private static final long   WINDOW_MILLIS = 60_000L;  // 1분 (ms)

    /** IP → [요청 횟수, 윈도우 시작 시각(ms)] */
    private final ConcurrentHashMap<String, long[]> counter = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri    = request.getRequestURI();
        String method = request.getMethod();
        // 로그인·이메일 인증 요청 전송 엔드포인트만 적용
        return !("POST".equalsIgnoreCase(method) &&
                (uri.equals("/api/auth/login") || uri.equals("/api/auth/email/request")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String ip  = resolveClientIp(request);
        long   now = Instant.now().toEpochMilli();

        long[] entry = counter.compute(ip, (key, val) -> {
            if (val == null || now - val[1] > WINDOW_MILLIS) {
                // 첫 요청이거나 윈도우 만료 → 새 윈도우 시작
                return new long[]{1L, now};
            }
            val[0]++;  // 같은 윈도우 내 횟수 증가
            return val;
        });

        if (entry[0] > MAX_REQUESTS) {
            log.warn("[RateLimit] 로그인 요청 초과 — ip={}, count={}", ip, entry[0]);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\": \"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 실제 클라이언트 IP 추출
     * AWS ALB 환경에서는 X-Forwarded-For 헤더에 원본 IP가 담김
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();  // 첫 번째 IP = 실제 클라이언트
        }
        return request.getRemoteAddr();
    }
}
