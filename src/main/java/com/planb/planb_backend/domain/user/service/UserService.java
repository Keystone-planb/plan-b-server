package com.planb.planb_backend.domain.user.service;

import com.planb.planb_backend.config.exception.BusinessException;
import com.planb.planb_backend.config.exception.ErrorCode;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.repository.TripRepository;
import com.planb.planb_backend.domain.user.dto.UpdateProfileRequest;
import com.planb.planb_backend.domain.user.dto.UpdateProfileResponse;
import com.planb.planb_backend.domain.user.dto.UserProfileResponse;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TripRepository tripRepository;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        List<String> preferredMoods = resolvePreferredMoods(user);

        return UserProfileResponse.builder()
                .email(user.getEmail())
                .nickname(user.getNickname())
                .provider(user.getProvider())
                .preferredMoods(preferredMoods)
                .build();
    }

    /**
     * 사용자의 모든 여행에서 선택한 travelStyles를 집계해
     * 가장 많이 선택된 Mood(들)을 반환한다.
     * 동률이면 해당 Mood를 모두 반환. 여행 이력이 없으면 빈 리스트.
     */
    private List<String> resolvePreferredMoods(User user) {
        Map<Mood, Long> moodCount = tripRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .flatMap(trip -> trip.getTravelStyles().stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        if (moodCount.isEmpty()) return Collections.emptyList();

        long maxCount = Collections.max(moodCount.values());

        return moodCount.entrySet().stream()
                .filter(e -> e.getValue() == maxCount)
                .map(e -> e.getKey().name())
                .collect(Collectors.toList());
    }

    @Transactional
    public void savePushToken(String email, String token) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.updateExpoPushToken(token);
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
