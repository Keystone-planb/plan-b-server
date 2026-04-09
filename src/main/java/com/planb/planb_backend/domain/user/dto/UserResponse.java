package com.planb.planb_backend.domain.user.dto;

import com.planb.planb_backend.domain.user.entity.User;
import lombok.Getter;

@Getter
public class UserResponse {

    private final Long id;
    private final String email;
    private final String nickname;
    private final String provider;
    private final String status;

    private UserResponse(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.nickname = user.getNickname();
        this.provider = user.getProvider();
        this.status = user.getStatus();
    }

    public static UserResponse from(User user) {
        return new UserResponse(user);
    }
}
