package com.planb.planb_backend.domain.place.dto;

import com.planb.planb_backend.domain.trip.entity.TransportMode;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserContext {

    private Long userId;
    private Long tripId;               // SOS 발동된 여행 ID — 중복 제외 및 다음 일정 추적에 사용
    private Long currentPlanId;

    private Double currentLat;
    private Double currentLng;
    private int radiusMinute;

    /**
     * 이동 수단 (WALK / TRANSIT / CAR).
     * null 이면 RecommendationService 에서 trip.transportMode 를 자동 적용하고,
     * 그것도 null 이면 WALK 로 폴백한다.
     */
    private TransportMode transportMode;

    private String selectedSpace;
    private String selectedType;

    /**
     * true: 현재 일정의 원본 구글 카테고리로 검색 → AI 2차 검열 스킵
     * false: selectedType으로 검색 → AI 2차 검열 적용 (기본값)
     */
    private boolean keepOriginalCategory;

    private boolean considerNextPlan;
    private Double nextLat;
    private Double nextLng;

    /**
     * [기능 6 — 틈새 추천] 추천된 장소가 이 시각에 영업 중이어야 함.
     * null 이면 영업시간 검사 비활성 (기존 SOS / 날씨 알림에서는 null 로 두면 됨).
     */
    private LocalDateTime mustBeOpenAt;

    /** RecommendationService에서 사용하는 카테고리 조회 편의 메서드 */
    public String getRequestedCategory() {
        return this.selectedType;
    }

    /**
     * 이동 수단 기반 분당 km 속도.
     * transportMode 가 없으면 WALK(0.08 km/min) 로 폴백.
     */
    public double getSpeedKmPerMin() {
        TransportMode m = (transportMode != null) ? transportMode : TransportMode.WALK;
        return m.getKmPerMin();
    }
}
