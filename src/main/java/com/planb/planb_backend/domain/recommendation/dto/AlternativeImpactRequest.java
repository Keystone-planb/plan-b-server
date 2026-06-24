package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AlternativeImpactRequest {

    /** 대안 장소 Google Place ID */
    private String newPlaceId;

    /** 대안 장소명 */
    private String newPlaceName;

    /** 대안 장소 위도 */
    private Double newLatitude;

    /** 대안 장소 경도 */
    private Double newLongitude;

    /**
     * 사용자가 선택한 이동수단 (WALK / TRANSIT / CAR)
     * null이면 여행의 기본 이동수단 사용
     * ReplaceConfirmSheet에서 사용자가 탭으로 선택 후 재조회 시 전달
     */
    private String selectedMode;
}
