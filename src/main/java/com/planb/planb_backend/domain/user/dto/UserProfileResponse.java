package com.planb.planb_backend.domain.user.dto;

import com.planb.planb_backend.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class UserProfileResponse {

    private String email;
    private String nickname;
    private String provider;
    private List<String> preferredMoods; // 최고 빈도 여행 스타일 (동률 시 여러 개)

    public static UserProfileResponse from(User user) {
        return UserProfileResponse.builder()
                .email(user.getEmail())
                .nickname(user.getNickname())
                .provider(user.getProvider())
                .build();
    }
}
