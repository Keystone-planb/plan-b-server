package com.planb.planb_backend.domain.preference.service;

import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.preference.dto.PreferenceSummaryResponse;
import com.planb.planb_backend.domain.preference.entity.UserPreference;
import com.planb.planb_backend.domain.preference.repository.UserPreferenceRepository;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceService {

    private final UserPreferenceRepository userPreferenceRepository;
    private final PlaceRepository          placeRepository;
    private final UserRepository           userRepository;

    private static final double SELECTED_DELTA    =  1.0;
    private static final double REJECTED_DELTA    = -0.3;
    private static final double ENOUGH_DATA_THRESHOLD = 1.0; // 최소 누적 점수 합산

    /**
     * 명시적 피드백 적용
     * - 선택된 장소의 mood: +1.0
     * - 노출됐지만 선택 안 된 장소의 mood: -0.3
     * mood가 null인 장소(미분석)는 조용히 스킵
     */
    @Transactional
    public void applyFeedback(Long userId, List<Long> shownPlaceIds, Long selectedPlaceId) {
        if (shownPlaceIds == null || shownPlaceIds.isEmpty()) {
            log.info("[Preference] shownPlaceIds 없음 — 피드백 스킵 (userId={})", userId);
            return;
        }
        for (Long placeId : shownPlaceIds) {
            Optional<Place> placeOpt = placeRepository.findById(placeId);
            if (placeOpt.isEmpty()) continue;

            Mood mood = placeOpt.get().getMood();
            if (mood == null) continue; // 미분석 장소 스킵

            double delta = placeId.equals(selectedPlaceId) ? SELECTED_DELTA : REJECTED_DELTA;
            updateScore(userId, mood, delta);
        }
        log.info("[Preference] 피드백 완료 — userId={}, selected={}", userId, selectedPlaceId);
    }

    /**
     * 취향 한 줄 요약
     * 누적 점수 합산이 ENOUGH_DATA_THRESHOLD 이상일 때 hasEnoughData = true
     */
    public PreferenceSummaryResponse getSummary(Long userId) {
        List<UserPreference> prefs = userPreferenceRepository.findByUserId(userId);

        double totalAbsScore = prefs.stream()
                .mapToDouble(p -> Math.abs(p.getScore()))
                .sum();

        boolean hasEnough = totalAbsScore >= ENOUGH_DATA_THRESHOLD;
        if (!hasEnough) {
            return PreferenceSummaryResponse.builder()
                    .userId(userId)
                    .hasEnoughData(false)
                    .message(null)
                    .build();
        }

        // 가장 점수 높은 mood 선택
        Mood topMood = prefs.stream()
                .max(Comparator.comparingDouble(UserPreference::getScore))
                .map(UserPreference::getMood)
                .orElse(null);

        String nickname = userRepository.findById(userId)
                .map(User::getNickname)
                .orElse("사용자");

        String message = topMood != null
                ? nickname + "님은 보통 " + toKorean(topMood) + "을(를) 선호하시네요"
                : null;

        return PreferenceSummaryResponse.builder()
                .userId(userId)
                .hasEnoughData(true)
                .message(message)
                .build();
    }

    /** 누적 점수 업데이트 (없으면 신규 생성) */
    private void updateScore(Long userId, Mood mood, double delta) {
        UserPreference pref = userPreferenceRepository
                .findByUserIdAndMood(userId, mood)
                .orElseGet(() -> new UserPreference(userId, mood));

        pref.setScore(pref.getScore() + delta);
        userPreferenceRepository.save(pref);
        log.debug("[Preference] userId={}, mood={}, delta={}, 누적={}", userId, mood, delta, pref.getScore());
    }

    private String toKorean(Mood mood) {
        return switch (mood) {
            case HEALING -> "힐링되는 조용한 곳";
            case ACTIVE  -> "활동적이고 역동적인 곳";
            case TRENDY  -> "트렌디하고 감각적인 곳";
            case CLASSIC -> "클래식하고 전통적인 곳";
            case LOCAL   -> "현지 분위기의 로컬 감성 곳";
        };
    }
}
