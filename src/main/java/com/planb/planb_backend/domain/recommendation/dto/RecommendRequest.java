package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class RecommendRequest {

    /** 현재 위치 */
    private Double currentLat;
    private Double currentLng;

    /** 이동 시간 반경 (분 단위) */
    private int radiusMinute;

    /** 도보 여부 (true=도보, false=차량) */
    private boolean walk;

    /** 원하는 공간 타입 (INDOOR / OUTDOOR / MIX) */
    private String selectedSpace;

    /** 원하는 장소 타입 (FOOD / CAFE / SIGHTS / ...) */
    private String selectedType;

    /** 기존 일정 타입 유지 여부 */
    private boolean keepOriginalType;

    /** 다음 목적지 고려 여부 (타원형 동선 보너스) */
    private boolean considerNextPlan;

    /** 다음 목적지 좌표 (considerNextPlan=true일 때) */
    private Double nextLat;
    private Double nextLng;
}
