package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ReplaceRequest {

    /** 새로 대체할 Google Place ID */
    private String newGooglePlaceId;

    /** 새 장소 이름 */
    private String newPlaceName;

    /** 새 장소 위도 (optional) — 있으면 이후 일정 시간 자동 재계산 */
    private Double newLatitude;

    /** 새 장소 경도 (optional) — 있으면 이후 일정 시간 자동 재계산 */
    private Double newLongitude;

    /** 새 방문 시작 시간 (optional, HH:mm) — 있으면 시간대 검증 후 장소 교체와 함께 원자적으로 저장 */
    private String visitTime;

    /** 새 방문 종료 시간 (optional, HH:mm) */
    private String endTime;
}
