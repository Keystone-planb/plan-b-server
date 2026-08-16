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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        // 추천 노출마다(recommendations/stream, gaps/recommend/stream 등 핵심 추천 플로우에서
        // 매번 호출됨) placeId마다 findById + findByUserIdAndMood + save를 반복하던 N×3 쿼리 제거
        // → 장소는 IN절 배치조회 1회, mood는 최대 5종(enum 크기)뿐이라 mood별로 delta를 합산한 뒤
        //   UserPreference도 배치조회 1회 + saveAll 1회로 처리
        Map<Long, Place> placeById = placeRepository.findAllById(shownPlaceIds).stream()
                .collect(Collectors.toMap(Place::getId, p -> p));

        Map<Mood, Double> deltaByMood = new EnumMap<>(Mood.class);
        for (Long placeId : shownPlaceIds) {
            Place place = placeById.get(placeId);
            if (place == null) continue;

            Mood mood = place.getMood();
            if (mood == null) continue; // 미분석 장소 스킵

            double delta = placeId.equals(selectedPlaceId) ? SELECTED_DELTA : REJECTED_DELTA;
            deltaByMood.merge(mood, delta, Double::sum);
        }

        if (deltaByMood.isEmpty()) {
            log.info("[Preference] 분석된 mood 없음 — 피드백 스킵 (userId={})", userId);
            return;
        }

        Map<Mood, UserPreference> existing = userPreferenceRepository
                .findByUserIdAndMoodIn(userId, new ArrayList<>(deltaByMood.keySet()))
                .stream()
                .collect(Collectors.toMap(UserPreference::getMood, p -> p));

        List<UserPreference> toSave = new ArrayList<>();
        deltaByMood.forEach((mood, delta) -> {
            UserPreference pref = existing.getOrDefault(mood, new UserPreference(userId, mood));
            pref.setScore(pref.getScore() + delta);
            toSave.add(pref);
        });
        userPreferenceRepository.saveAll(toSave);

        log.info("[Preference] 피드백 완료 — userId={}, selected={}, 반영된 mood 수={}",
                userId, selectedPlaceId, deltaByMood.size());
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
