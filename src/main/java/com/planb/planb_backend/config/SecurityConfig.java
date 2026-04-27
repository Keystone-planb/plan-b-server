package com.planb.planb_backend.config;

import com.planb.planb_backend.auth.CustomOAuth2UserService;
import com.planb.planb_backend.auth.OAuth2RedirectUriFilter;
import com.planb.planb_backend.auth.OAuth2SuccessHandler;
import com.planb.planb_backend.jwt.JwtFilter;
import com.planb.planb_backend.jwt.JwtProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Value("${oauth2.failure-redirect-uri}")
    private String defaultFailureRedirectUri;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(restAuthenticationEntryPoint)
                .accessDeniedHandler(restAccessDeniedHandler)
            )

            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/users/signup").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/email/request").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/email/verify").permitAll()
                .requestMatchers("/oauth2/**").permitAll()
                .requestMatchers("/login/oauth2/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/swagger-ui/**").permitAll()
                .requestMatchers("/swagger-ui.html").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )

            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo ->
                    userInfo.userService(customOAuth2UserService))
                .successHandler(oAuth2SuccessHandler)
                .failureHandler((request, response, exception) -> {
                    // 실패 시에도 쿠키에서 redirect_uri 읽어서 failure로 보냄
                    String targetUri = defaultFailureRedirectUri;
                    if (request.getCookies() != null) {
                        targetUri = Arrays.stream(request.getCookies())
                                .filter(c -> OAuth2RedirectUriFilter.COOKIE_NAME.equals(c.getName()))
                                .map(Cookie::getValue)
                                .findFirst()
                                .map(uri -> uri.replace("/oauth/success", "/oauth/failure"))
                                .orElse(defaultFailureRedirectUri);
                    }
                    // 쿠키 삭제
                    Cookie clear = new Cookie(OAuth2RedirectUriFilter.COOKIE_NAME, "");
                    clear.setPath("/");
                    clear.setMaxAge(0);
                    response.addCookie(clear);

                    String redirectUrl = UriComponentsBuilder.fromUriString(targetUri)
                            .queryParam("error", "소셜 로그인에 실패했습니다.")
                            .build().toUriString();
                    response.sendRedirect(redirectUrl);
                })
            )

            // OAuth2 시작 시 redirect_uri 쿠키 저장 필터
            // Spring의 OAuth2AuthorizationRequestRedirectFilter보다 반드시 먼저 실행되어야 쿠키가 저장됨
            .addFilterBefore(new OAuth2RedirectUriFilter(), OAuth2AuthorizationRequestRedirectFilter.class)
            .addFilterBefore(new JwtFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",                    // CRA
                "http://localhost:5173",                    // Vite
                "http://localhost:8081",                    // Expo Web
                "http://localhost:8082",                    // Expo Web (대체 포트)
                "https://api-dev.planb-travel.cloud"       // api-dev 통합 테스트 서버
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
