package com.planb.planb_backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 모든 HTTP 요청에 고유한 traceId를 부여하는 서블릿 필터.
 *
 * - @Order(HIGHEST_PRECEDENCE): Spring Security 필터 체인보다 먼저 실행
 *   → JwtFilter, OAuth2 필터 포함 모든 처리에 traceId가 존재
 * - 응답 헤더 X-Trace-Id: 프론트엔드 / CloudWatch 로그 연계에 활용 가능
 * - finally MDC.clear(): 스레드 풀 재사용 시 이전 traceId 잔존 방지
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
