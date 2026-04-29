package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class RecommendRequest {

    /** 사용자 / 여행 식별 */
    private Long userId;
    private Long tripId;                       // 중복 제외 및 다음 일정 추적에 사용
    private Long currentPlanId;                // keepOriginalCategory=true 일 때 원본 카테고리 조회용

    /** 현재 위치 */
    private Double currentLat;
    private Double currentLng;

    /** 이동 조건 */
    private int radiusMinute;
    private boolean walk;                      // true=도보(80m/분), false=차량(400m/분)

    /** 장소 필터 */
    private String selectedSpace;             // INDOOR / OUTDOOR / MIX
    private String selectedType;              // FOOD / CAFE / SIGHTS / SHOP / MARKET / THEME / CULTURE / PARK

    /**
     * true: 현재 일정 원본 구글 카테고리 유지 → AI 2차 검열 스킵
     * false: selectedType 기준 검색 + AI 2차 검열 적용 (기본값)
     */
    private boolean keepOriginalCategory;

    /** 동선 최적화 */
    private boolean considerNextPlan;         // true 시 타원형 보너스 활성화
    private Double nextLat;                   // 다음 목적지 위도 (null 이면 DB에서 자동 조회)
    private Double nextLng;                   // 다음 목적지 경도
}
