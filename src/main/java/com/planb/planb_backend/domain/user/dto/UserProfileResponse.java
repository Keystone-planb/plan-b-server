package com.planb.planb_backend.domain.user.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

@Getter
@Builder
public class UserProfileResponse {

    private Long id;
    private String email;
    private String nickname;
    private String provider;
    private String travelStyle;
    private List<String> preferences;

    public static UserProfileResponse from(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .provider(user.getProvider())
                .travelStyle(user.getTravelStyle())
                .preferences(parsePreferences(user.getPreferences()))
                .build();
    }

    private static List<String> parsePreferences(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
