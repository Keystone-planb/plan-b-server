package com.planb.planb_backend.domain.place.dto;

import com.planb.planb_backend.domain.trip.entity.TransportMode;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * [기능 6 — 틈새 추천] 두 일정 사이의 "비는 시간" 정보.
 * <p>
 * GET /api/trips/{tripId}/gaps 응답 엘리먼트.
 */
@Getter
@Builder
public class GapInfo {

    /** 직전 일정 */
    private Long beforePlanId;
    private String beforePlanTitle;
    private LocalDateTime beforePlanEndTime;
    private Double beforePlaceLat;
    private Double beforePlaceLng;

    /** 직후 일정 */
    private Long afterPlanId;
    private String afterPlanTitle;
    private LocalDateTime afterPlanStartTime;
    private Double afterPlaceLat;
    private Double afterPlaceLng;

    /** 갭 계산 결과 */
    private int gapMinutes;            // 총 빈 시간 (분)
    private int estimatedTravelMinutes; // 두 장소 간 예상 이동 시간 (직선 기반)
    private int availableMinutes;      // 실제 활용 가능 시간 = gapMinutes − travelMinutes − 안전마진
    private TransportMode transportMode;
}
