package com.planb.planb_backend.domain.user.dto;

import com.planb.planb_backend.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateProfileResponse {

    private String email;
    private String nickname;
    private String message;

    public static UpdateProfileResponse from(User user) {
        return UpdateProfileResponse.builder()
                .email(user.getEmail())
                .nickname(user.getNickname())
                .message("프로필 정보가 성공적으로 수정되었습니다.")
                .build();
    }
}
