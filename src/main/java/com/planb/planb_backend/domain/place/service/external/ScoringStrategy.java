package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.preference.repository.UserPreferenceRepository;
import com.planb.planb_backend.domain.trip.entity.Mood;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ScoringStrategy {

    // @RequiredArgsConstructor 미사용 → 기존 코드 변경 없이 field injection
    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    public double calculateScore(Place candidate, UserContext context) {
        // 1. 기초 품질 점수
        double rating = (candidate.getRating() != null) ? candidate.getRating() : 0.0;
        int reviewCount = (candidate.getUserRatingsTotal() != null) ? candidate.getUserRatingsTotal() : 0;
        double baseScore = rating * Math.log10(reviewCount + 1);

        // 2. 사용자 필터 가중치 (filterMultiplier)
        double filterMultiplier = 1.0;

        // (1) Space 필터
        if (context.getSelectedSpace() != null && candidate.getSpace() != null) {
            if (context.getSelectedSpace().equalsIgnoreCase(candidate.getSpace().name())) {
                filterMultiplier *= 2.0;
            } else {
                filterMultiplier *= 0.2;
            }
        }

        // (2) Type 필터
        // keepOriginalCategory=true 이면 selectedType이 무의미하므로 가중치 적용 스킵
        String targetType = context.isKeepOriginalCategory() ? null : context.getSelectedType();

        if (targetType != null && !targetType.isEmpty() && candidate.getType() != null) {
            if (targetType.equalsIgnoreCase(candidate.getType().name())) {
                filterMultiplier *= 1.5;
            }
        }

        // 3. 거리 페널티 (이동 수단 반영)
        double distanceKm = calculateHaversine(context.getCurrentLat(), context.getCurrentLng(),
                candidate.getLatitude(), candidate.getLongitude());

        double speedKmPerMin = context.getSpeedKmPerMin();
        double userRadiusKm = context.getRadiusMinute() * speedKmPerMin;

        double distancePenalty = 1.0;
        if (distanceKm > userRadiusKm) {
            double excessRatio = (distanceKm - userRadiusKm) / userRadiusKm;
            distancePenalty = Math.max(0.1, 1.0 - excessRatio);
        }

        double finalScore = baseScore * filterMultiplier * distancePenalty;

        // 4. 타원형 동선 가중치 (길목 보너스)
        if (context.isConsiderNextPlan() && context.getNextLat() != null) {
            double distToNext = calculateHaversine(candidate.getLatitude(), candidate.getLongitude(),
                    context.getNextLat(), context.getNextLng());
            double directDist = calculateHaversine(context.getCurrentLat(), context.getCurrentLng(),
                    context.getNextLat(), context.getNextLng());

            double detourRatio = (distanceKm + distToNext) / directDist;
            if (detourRatio < 1.2) {
                finalScore *= 2.5;
            }
        }

        // 5. Mood 개인화 가중치 — 모든 스코어링이 끝난 후 마지막에 적용
        // 공식: 1 + score × 0.15, 범위 클램프 [0.7, 2.0]
        if (context.getUserId() != null && candidate.getMood() != null) {
            finalScore *= getMoodPreferenceMultiplier(context.getUserId(), candidate.getMood());
        }

        return finalScore;
    }

    /**
     * Mood 개인화 가중치 계산
     * - 공식: 1 + score × 0.15
     * - 클램프: [0.7, 2.0] — 과도한 편향 방지
     * - 선호 이력 없으면 1.0 (중립)
     */
    private double getMoodPreferenceMultiplier(Long userId, Mood mood) {
        try {
            return userPreferenceRepository.findByUserIdAndMood(userId, mood)
                    .map(pref -> {
                        double raw = 1.0 + pref.getScore() * 0.15;
                        double clamped = Math.min(2.0, Math.max(0.7, raw));
                        log.debug("[MoodPreference] userId={}, mood={}, score={}, multiplier={}",
                                userId, mood, pref.getScore(), clamped);
                        return clamped;
                    })
                    .orElse(1.0);
        } catch (Exception e) {
            log.warn("[MoodPreference] 가중치 조회 실패 — 중립 적용: {}", e.getMessage());
            return 1.0;
        }
    }

    /** 외부 서비스(RecommendationService 1차 퍼널 등)에서 사용 가능하도록 public으로 노출 */
    public double haversine(double lat1, double lon1, double lat2, double lon2) {
        return calculateHaversine(lat1, lon1, lat2, lon2);
    }

    private double calculateHaversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
