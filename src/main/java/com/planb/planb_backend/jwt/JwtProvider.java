package com.planb.planb_backend.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtProvider {

    private final SecretKey secretKey;

    // Access Token 유효 시간: 1시간
    private static final long ACCESS_TOKEN_EXPIRATION = 1000L * 60 * 60;

    public JwtProvider(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Access Token 생성 (subject: email, claim: role, 유효: 1시간)
     */
    public String createAccessToken(String email, String role) {
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRATION))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Refresh Token 생성 (UUID 기반, 만료 관리는 DB에서 수행)
     */
    public String createRefreshToken(Long userId) {
        return UUID.randomUUID().toString();
    }

    /**
     * Access Token 유효성 + 만료 여부 검증
     */
    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] 만료된 토큰: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("[JWT] 유효하지 않은 토큰 ({}): {}", e.getClass().getSimpleName(), e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("[JWT] 토큰 값이 비어있거나 잘못된 형식: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 토큰에서 이메일(subject) 추출
     */
    public String getEmailFromToken(String token) {
        return getClaims(token).getSubject();
    }

    /**
     * 토큰에서 role 클레임 추출
     */
    public String getRoleFromToken(String token) {
        return (String) getClaims(token).get("role");
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
