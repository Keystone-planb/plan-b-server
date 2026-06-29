package com.planb.planb_backend.domain.trip.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.util.List;

@Getter
public class RecoveryConfirmRequest {

    /**
     * AI 서버 recovery_done 이벤트의 places 배열을 그대로 전달.
     * 장소 교체 + 시간 조정이 모두 반영된 최종 상태.
     */
    @NotNull(message = "places는 필수입니다.")
    private List<PlaceItem> places;

    @Getter
    public static class PlaceItem {
        /** TripPlace PK */
        private Long tripPlaceId;

        /** 교체된 경우 새 Google Place ID, 변경 없으면 기존 값 */
        private String placeId;

        /** 교체된 경우 새 장소명, 변경 없으면 기존 값 */
        private String name;

        /** 재계산된 방문 시작 시간 "HH:mm" */
        private String visitTime;

        /** 재계산된 방문 종료 시간 "HH:mm" */
        private String endTime;

        /** 새 장소 위도 (장소 교체 시에만 유효) */
        private Double latitude;

        /** 새 장소 경도 (장소 교체 시에만 유효) */
        private Double longitude;

        /** 새 장소 카테고리 (장소 교체 시에만 유효) */
        private String category;

        /** 새 장소 타입 (장소 교체 시에만 유효) */
        private String type;
    }
}
