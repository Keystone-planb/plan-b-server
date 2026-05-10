package com.planb.planb_backend.domain.user.service;

import com.planb.planb_backend.config.exception.BusinessException;
import com.planb.planb_backend.config.exception.ErrorCode;
import com.planb.planb_backend.domain.user.dto.UpdateProfileRequest;
import com.planb.planb_backend.domain.user.dto.UpdateProfileResponse;
import com.planb.planb_backend.domain.user.dto.UserProfileResponse;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        return UserProfileResponse.from(user);
    }

    @Transactional
    public UpdateProfileResponse updateProfile(String email, UpdateProfileRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 닉네임 변경
        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            user.updateNickname(request.getNickname());
        }

        // 비밀번호 변경
        if (request.getNewPassword() != null && !request.getNewPassword().isBlank()) {
            // 소셜 로그인 유저 방어
            if (!"local".equalsIgnoreCase(user.getProvider())) {
                throw new BusinessException(ErrorCode.SOCIAL_USER_CANNOT_CHANGE_PASSWORD);
            }
            // 현재 비밀번호 확인
            if (request.getCurrentPassword() == null
                    || !passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw new BusinessException(ErrorCode.INVALID_PASSWORD);
            }
            user.updatePassword(passwordEncoder.encode(request.getNewPassword()));
        }

        return UpdateProfileResponse.from(user);
    }
}
