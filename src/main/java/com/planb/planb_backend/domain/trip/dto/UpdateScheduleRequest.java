package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.TransportMode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PATCH /api/plans/{planId}/schedule
 * 장소는 그대로, 방문 시간/메모/이동수단 수정
 */
@Getter
@NoArgsConstructor
public class UpdateScheduleRequest {

    private String visitTime;           // 시작 시간 "HH:mm" (null이면 기존 값 유지)
    private String endTime;             // 종료 시간 "HH:mm" (null이면 기존 값 유지)
    private String memo;                // 메모 (null이면 기존 값 유지)
    private TransportMode transportMode; // 이 장소 → 다음 장소 이동 수단 (null이면 기존 값 유지)
}
