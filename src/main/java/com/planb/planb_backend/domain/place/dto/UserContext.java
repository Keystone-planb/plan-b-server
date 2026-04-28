package com.planb.planb_backend.domain.place.dto;

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
    private LocalDateTime currentPlanStartTime;

    private Double currentLat;
    private Double currentLng;
    private int radiusMinute;

    private boolean walk;

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

    /** RecommendationService에서 사용하는 카테고리 조회 편의 메서드 */
    public String getRequestedCategory() {
        return this.selectedType;
    }
}
