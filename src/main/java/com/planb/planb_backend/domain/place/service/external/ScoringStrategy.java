package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.dto.UserContext;
import org.springframework.stereotype.Component;

@Component
public class ScoringStrategy {

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
        String targetType = context.getSelectedType();

        if (context.isKeepOriginalType()) {
            // TODO: 기존 일정의 장소 타입을 가져오는 로직 추가 가능
        }

        if (targetType != null && !targetType.isEmpty() && candidate.getType() != null) {
            if (targetType.equalsIgnoreCase(candidate.getType().name())) {
                filterMultiplier *= 1.5;
            }
        }

        // 3. 거리 페널티 (isWalk 반영)
        double distanceKm = calculateHaversine(context.getCurrentLat(), context.getCurrentLng(),
                candidate.getLatitude(), candidate.getLongitude());

        double speedKmPerMin = context.isWalk() ? 0.08 : 0.4;
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

        return finalScore;
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
