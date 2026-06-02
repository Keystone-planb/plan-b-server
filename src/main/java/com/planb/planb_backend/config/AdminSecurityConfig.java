package com.planb.planb_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

/**
 * 어드민 정적 파일(/admin/**) Security 제외 설정
 * - WebSecurityCustomizer를 추가 등록하면 Spring이 모두 합산해서 적용해줌
 * - 기존 SecurityConfig.java 무수정 (Zero Risk)
 * - /admin/index.html, /admin/app.js → Spring Security 필터 완전 우회
 * - /api/admin/** 는 기존 체인의 anyRequest().authenticated() 가 그대로 적용 (JWT 필요)
 */
@Configuration
public class AdminSecurityConfig {

    @Bean
    public WebSecurityCustomizer adminWebSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/admin/**");
    }
}
