package com.planb.planb_backend.domain.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.user.dto.UpdatePreferenceRequest;
import com.planb.planb_backend.domain.user.dto.UserProfileResponse;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String email) {
        User user = findByEmail(email);
        return UserProfileResponse.from(user);
    }

    @Transactional
    public void updatePreferences(String email, UpdatePreferenceRequest request) {
        User user = findByEmail(email);
        String preferencesJson = serializePreferences(request);
        user.updatePreferences(request.getTravelStyle(), preferencesJson);
    }

    private User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    private String serializePreferences(UpdatePreferenceRequest request) {
        if (request.getPreferences() == null || request.getPreferences().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(request.getPreferences());
        } catch (Exception e) {
            return null;
        }
    }
}
