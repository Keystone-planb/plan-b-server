package com.planb.planb_backend.domain.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.util.List;

@Getter
public class OptimizeConfirmRequest {

    /** 선택한 대안 장소의 Google Place ID */
    @NotBlank(message = "newPlaceId는 필수입니다.")
    private String newPlaceId;

    /** 선택한 대안 장소명 */
    @NotBlank(message = "newName은 필수입니다.")
    private String newName;

    /** 선택한 대안 장소 위도 */
    @NotNull(message = "newLatitude는 필수입니다.")
    private Double newLatitude;

    /** 선택한 대안 장소 경도 */
    @NotNull(message = "newLongitude는 필수입니다.")
    private Double newLongitude;

    /** 선택한 대안 장소 카테고리 */
    private String newCategory;

    /** 선택한 대안 장소 타입 */
    private String newType;

    /**
     * 이후 장소 시간 수동 지정 (선택값).
     * null 또는 빈 배열이면 백엔드가 Google Distance Matrix API로 자동 재계산한다.
     * AI 서버 SSE affectedTimes 값을 그대로 전달할 때만 사용.
     */
    private List<AffectedTimeItem> affectedTimes;

    @Getter
    public static class AffectedTimeItem {
        private Long tripPlaceId;
        private String newVisitTime;
        private String newEndTime;
    }
}
