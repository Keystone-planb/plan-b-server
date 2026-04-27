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
    private Long currentPlanId;
    private LocalDateTime currentPlanStartTime;

    private Double currentLat;
    private Double currentLng;
    private int radiusMinute;

    private boolean walk;

    private String selectedSpace;
    private String selectedType;

    private boolean keepOriginalType;

    private boolean considerNextPlan;
    private Double nextLat;
    private Double nextLng;

    /** RecommendationService에서 사용하는 카테고리 조회 편의 메서드 */
    public String getRequestedCategory() {
        return this.selectedType;
    }
}
