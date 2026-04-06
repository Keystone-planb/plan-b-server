package com.planb.planb_backend.domain.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {

    private boolean success;
    private String message;
    private String accessToken;
    private String refreshToken;
    private String tokenType;
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
