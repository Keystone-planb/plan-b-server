package com.planb.planb_backend.domain.place.dto;

import com.planb.planb_backend.domain.trip.entity.TransportMode;
import lombok.*;

/**
 * [기능 6 — 틈새 추천] 추천 요청 바디.
 * <p>
 * 클라이언트는 어떤 갭에 대해 추천을 요청하는지 두 일정 ID 만 보내면 된다.
 * 위치/가용시간/영업상태 등 나머지는 서버가 GapDetectionService 로 계산한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GapRecommendationRequest {

    private Long userId;
    private Long tripId;

    /** 갭 직전 일정 ID (TripPlace.tripPlaceId) */
    private Long beforePlanId;

    /** 갭 직후 일정 ID (TripPlace.tripPlaceId) */
    private Long afterPlanId;

    /**
     * 이동 수단 override. 미설정 시 trip.transportMode 를 자동 사용.
     */
    private TransportMode transportMode;

    /**
     * 검색 반경 (분 단위). 미설정 시 가용시간 기반으로 서버가 자동 산출.
     */
    private Integer radiusMinute;
}
