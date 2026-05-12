package com.planb.planb_backend.domain.trip.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "trip_places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class TripPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trip_place_id")
    private Long tripPlaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Column(name = "place_id", nullable = false, length = 100)
    private String placeId;     // Google Place ID

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "visit_time", length = 10)
    private String visitTime;   // 시작 시간 "HH:mm"

    @Column(name = "end_time", length = 10)
    private String endTime;     // 종료 시간 "HH:mm"

    @Column(name = "visit_order")
    private int visitOrder;     // 방문 순서

    @Column(length = 500)
    private String memo;        // 사용자 메모

    /**
     * PLAN B 대체: 장소 ID와 이름만 교체
     * visitTime/endTime은 새 장소 기준으로 다시 설정해야 하므로 null 초기화
     * memo는 기획 변경으로 기존 값 그대로 유지
     */
    public void replace(String newPlaceId, String newPlaceName) {
        this.placeId   = newPlaceId;
        this.name      = "[" + newPlaceName + "] (PLAN B)";
        this.visitTime = null;
        this.endTime   = null;
        // memo 유지 (기존 메모 보존)
    }

    /** 시간/메모 수정: 장소는 그대로, null 필드는 기존 값 유지 */
    public void updateSchedule(String visitTime, String endTime, String memo) {
        if (visitTime != null) this.visitTime = visitTime;
        if (endTime   != null) this.endTime   = endTime;
        if (memo      != null) this.memo      = memo;
    }
}
