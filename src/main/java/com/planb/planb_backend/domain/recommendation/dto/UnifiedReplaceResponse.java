package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 장소 교체 확정 통합 응답 DTO
 * SOS 대안 추천 / 날씨 푸시 알림 / 틈새 추천 3가지 경로 모두 동일 스펙으로 응답
 */
@Getter
@Builder
public class UnifiedReplaceResponse {

    /** 교체된 TripPlace PK */
    private Long tripPlaceId;

    /** 새 장소 Google Place ID */
    private String newGooglePlaceId;

    /** 새 장소명 — "[장소명] (PLAN B)" 형식 */
    private String newPlaceName;

    /** 교체 후 방문 시작 시간 (원본 시간대 승계) */
    private String visitTime;

    /** 교체 후 방문 종료 시간 (원본 시간대 승계) */
    private String endTime;

    /**
     * 이후 일정 시간 변경 목록
     * 좌표 기반 재계산이 불가하거나 후속 일정이 없으면 빈 리스트
     */
    private List<UpdatedSchedule> updatedSchedules;

    @Getter
    @Builder
    public static class UpdatedSchedule {
        private Long   tripPlaceId;
        private String name;
        private String visitTime;
        private String endTime;
        private int    travelMinFromPrev;
    }
}
