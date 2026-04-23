package com.planb.planb_backend.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {

    private boolean success;
    private String message;

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("user_id")
    private Long userId;

    private String nickname;

    // 회원가입 성공 응답
    public static AuthResponse ofSignup(Long userId) {
        return AuthResponse.builder()
                .success(true)
                .message("회원가입이 완료되었습니다.")
                .userId(userId)
                .build();
    }

    // Access Token 재발급 응답 (refreshToken 엔드포인트)
    public static AuthResponse ofAccessToken(String accessToken, Long userId, String nickname) {
        return AuthResponse.builder()
                .success(true)
                .message("Access Token이 재발급되었습니다.")
                .accessToken(accessToken)
                .tokenType("Bearer")
                .userId(userId)
                .nickname(nickname)
                .build();
    }

    // 로그인 성공 응답
    public static AuthResponse ofLogin(String accessToken, String refreshToken, Long userId, String nickname) {
        return AuthResponse.builder()
                .success(true)
                .message("로그인에 성공하였습니다.")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .userId(userId)
                .nickname(nickname)
                .build();
    }
}
