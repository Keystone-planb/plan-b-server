package com.planb.planb_backend.domain.trip.entity;

import jakarta.persistence.*;
import lombok.*;
import com.planb.planb_backend.domain.trip.entity.PlaceSource;
import com.planb.planb_backend.domain.trip.entity.TransportMode;

import java.util.ArrayList;
import java.util.List;

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

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PlaceSource source; // 추가 출처 (NORMAL / SOS / WEATHER / GAP), 기존 데이터는 null

    /** 이 장소 → 다음 장소 이동 수단. null 이면 Trip.transportMode 폴백 */
    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", length = 20)
    private TransportMode transportMode;

    @OneToMany(mappedBy = "tripPlace", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<TripPlaceMemo> memos = new ArrayList<>();

    /**
     * PLAN B 대체: 장소 ID와 이름만 교체
     * visitTime/endTime은 원본 시간대 그대로 승계 (날씨·SOS 대안은 같은 시간대에 다른 장소로 교체하는 것)
     * memo는 기존 값 그대로 유지
     */
    public void replace(String newPlaceId, String newPlaceName) {
        this.placeId = newPlaceId;
        this.name    = "[" + newPlaceName + "] (PLAN B)";
        // visitTime / endTime 유지 (원본 시간대 승계)
        // memo 유지
    }

    /** 시간/메모/이동수단 수정: 장소는 그대로, null 필드는 기존 값 유지 */
    public void updateSchedule(String visitTime, String endTime, String memo, TransportMode transportMode) {
        if (visitTime      != null) this.visitTime      = visitTime;
        if (endTime        != null) this.endTime        = endTime;
        if (memo           != null) this.memo           = memo;
        if (transportMode  != null) this.transportMode  = transportMode;
    }

    /** 방문 순서 변경 */
    public void updateOrder(int newOrder) {
        this.visitOrder = newOrder;
    }

    /** 다른 일차로 이동: itinerary 교체 + 순서 재배정 */
    public void moveToItinerary(Itinerary targetItinerary, int newOrder) {
        this.itinerary  = targetItinerary;
        this.visitOrder = newOrder;
    }
}
