package com.planb.planb_backend.domain.trip.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OptimizeConfirmResponse {

    private String message;

    /** Distance Matrix로 재계산된 뒤 일정 목록 */
    private List<UpdatedScheduleItem> updatedSchedules;

    @Getter
    @Builder
    public static class UpdatedScheduleItem {
        private Long   tripPlaceId;
        private String name;
        private String visitTime;          // 재계산된 시작 시간 "HH:mm"
        private String endTime;            // 재계산된 종료 시간 "HH:mm"
        private int    travelMinFromPrev;  // 이전 장소에서 실제 이동시간(분)
    }
}
