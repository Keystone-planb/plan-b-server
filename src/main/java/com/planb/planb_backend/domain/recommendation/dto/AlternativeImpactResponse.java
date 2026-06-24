package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 대안 장소 선택 시 일정 영향 미리보기 응답
 * 프론트엔드 ReplaceConfirmSheet 타임라인 렌더링에 사용
 */
@Getter
@Builder
public class AlternativeImpactResponse {

    /**
     * 계산 상태
     * - OK       : 정상 계산
     * - NO_COORD : 대안 장소 좌표 미전달 (요청 body 확인 필요)
     */
    private String calcStatus;

    /**
     * 이전 장소 → 대안 장소 이동시간 옵션 3가지 (도보/대중교통/자동차)
     * 이전 장소 없으면 빈 리스트
     */
    private List<TravelOption> travelInOptions;

    /**
     * 대안 장소 → 다음 장소 이동시간 옵션 3가지 (도보/대중교통/자동차)
     * 다음 장소 없으면 빈 리스트
     */
    private List<TravelOption> travelOutOptions;

    /**
     * 현재 설정된 이동수단 기준 이동시간 (기존 로직 호환용 — 단일 값)
     * travelInOptions 중 selectedMode에 해당하는 값과 동일
     */
    private Integer travelInMin;
    private String  travelInMode;
    private Integer travelOutMin;
    private String  travelOutMode;

    /** 이전 장소 정보 (첫 번째 일정이면 null) */
    private PlaceSnapshot prevPlace;

    /** 다음 장소 정보 + 교체 후 예상 시간 (마지막 일정이면 null) */
    private PlaceSnapshot nextPlace;

    /** 교체 후 이후 일정이 밀리는 총 시간(분). 양수=지연, 음수=앞당겨짐 */
    private Integer dayShiftMin;

    /** 시간이 변경되는 후속 일정 수 */
    private Integer affectedCount;

    /** 이동수단별 소요시간 1개 항목 */
    @Getter
    @Builder
    public static class TravelOption {
        /** WALK / TRANSIT / CAR */
        private String mode;
        /** 소요시간(분) */
        private int    minutes;
        /** 표시용 레이블 (도보 / 대중교통 / 자동차) */
        private String label;

        public static TravelOption of(String mode, int minutes) {
            String label = switch (mode) {
                case "WALK"    -> "도보";
                case "TRANSIT" -> "대중교통";
                case "CAR"     -> "자동차";
                default        -> mode;
            };
            return TravelOption.builder()
                    .mode(mode).minutes(minutes).label(label)
                    .build();
        }
    }

    @Getter
    @Builder
    public static class PlaceSnapshot {
        private Long   tripPlaceId;
        private String name;
        private String visitTime;
        private String endTime;
        /** 교체 후 예상 시작 시간 — nextPlace 전용, prevPlace는 null */
        private String newVisitTime;
    }
}
