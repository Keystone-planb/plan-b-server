package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.GapInfo;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.trip.entity.*;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.trip.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * [기능 6 — 틈새 추천] GapDetectionService 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
class GapDetectionServiceTest {

    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private TripRepository tripRepository;
    @Mock private PlaceRepository placeRepository;

    @InjectMocks
    private GapDetectionService service;

    private static final Long TRIP_ID = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 4);

    // ── 헬퍼 ────────────────────────────────────────────────────────

    private Trip makeTrip(TransportMode mode) {
        return Trip.builder()
                .tripId(TRIP_ID)
                .title("테스트 여행")
                .startDate(TODAY)
                .endDate(TODAY)
                .user(null)
                .transportMode(mode)
                .build();
    }

    private Itinerary makeItinerary(Trip trip) {
        return Itinerary.builder()
                .itineraryId(10L)
                .trip(trip)
                .day(1)
                .date(TODAY)
                .build();
    }

    private TripPlace makePlace(Long id, Itinerary itinerary, String placeId,
                                String name, String visitTime, String endTime, int order) {
        return TripPlace.builder()
                .tripPlaceId(id)
                .itinerary(itinerary)
                .placeId(placeId)
                .name(name)
                .visitTime(visitTime)
                .endTime(endTime)
                .visitOrder(order)
                .build();
    }

    private Place makePlaceEntity(Double lat, Double lng) {
        Place p = new Place();
        p.setLatitude(lat);
        p.setLongitude(lng);
        return p;
    }

    // ── 테스트 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("30분 이상 갭 → GapInfo 1건 반환")
    void detectGap_moreThan30min_returnsGap() {
        Trip trip = makeTrip(TransportMode.WALK);
        Itinerary itin = makeItinerary(trip);

        // A: 10:00~11:00, B: 12:30 시작 → 갭 90분
        TripPlace before = makePlace(1L, itin, "place_a", "경복궁", "10:00", "11:00", 1);
        TripPlace after  = makePlace(2L, itin, "place_b", "북촌한옥마을", "12:30", null, 2);

        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(TRIP_ID))
                .thenReturn(List.of(before, after));
        when(placeRepository.findByGooglePlaceId("place_a"))
                .thenReturn(Optional.of(makePlaceEntity(37.5796, 126.9770)));
        when(placeRepository.findByGooglePlaceId("place_b"))
                .thenReturn(Optional.of(makePlaceEntity(37.5820, 126.9850)));

        List<GapInfo> gaps = service.detectGaps(TRIP_ID);

        assertThat(gaps).hasSize(1);
        GapInfo gap = gaps.get(0);
        assertThat(gap.getBeforePlanId()).isEqualTo(1L);
        assertThat(gap.getAfterPlanId()).isEqualTo(2L);
        assertThat(gap.getGapMinutes()).isEqualTo(90);
        assertThat(gap.getTransportMode()).isEqualTo(TransportMode.WALK);
        assertThat(gap.getAvailableMinutes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("29분 갭 → MIN_GAP_MINUTES(30) 미만이므로 갭 없음")
    void detectGap_lessThan30min_returnsEmpty() {
        Trip trip = makeTrip(TransportMode.WALK);
        Itinerary itin = makeItinerary(trip);

        // 10:00~11:00 → 11:29 시작: 갭 29분
        TripPlace before = makePlace(1L, itin, "a", "A", "10:00", "11:00", 1);
        TripPlace after  = makePlace(2L, itin, "b", "B", "11:29", null, 2);

        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(TRIP_ID))
                .thenReturn(List.of(before, after));

        List<GapInfo> gaps = service.detectGaps(TRIP_ID);

        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("endTime 없을 때 visitTime+60분으로 계산")
    void detectGap_noEndTime_usesVisitTimePlusSixty() {
        Trip trip = makeTrip(TransportMode.WALK);
        Itinerary itin = makeItinerary(trip);

        // endTime 없는 before: visitTime 10:00 → 기본 11:00으로 계산
        // after: 12:30 시작 → 갭 90분
        TripPlace before = makePlace(1L, itin, "a", "A", "10:00", null, 1);
        TripPlace after  = makePlace(2L, itin, "b", "B", "12:30", null, 2);

        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(TRIP_ID))
                .thenReturn(List.of(before, after));
        when(placeRepository.findByGooglePlaceId(any())).thenReturn(Optional.empty());

        List<GapInfo> gaps = service.detectGaps(TRIP_ID);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).getGapMinutes()).isEqualTo(90);
    }

    @Test
    @DisplayName("서로 다른 날짜 일정 사이는 갭으로 인정하지 않음")
    void detectGap_differentDate_noGap() {
        Trip trip = makeTrip(TransportMode.WALK);
        Itinerary today = makeItinerary(trip);
        Itinerary tomorrow = Itinerary.builder()
                .itineraryId(11L).trip(trip).day(2).date(TODAY.plusDays(1)).build();

        TripPlace before = makePlace(1L, today,   "a", "A", "10:00", "11:00", 1);
        TripPlace after  = makePlace(2L, tomorrow, "b", "B", "14:00", null,   1);

        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(TRIP_ID))
                .thenReturn(List.of(before, after));

        List<GapInfo> gaps = service.detectGaps(TRIP_ID);

        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("trip.transportMode=null 이면 WALK 폴백")
    void detectGap_nullTripMode_fallsBackToWalk() {
        Trip trip = makeTrip(null); // transportMode = null
        Itinerary itin = makeItinerary(trip);

        TripPlace before = makePlace(1L, itin, "a", "A", "10:00", "11:00", 1);
        TripPlace after  = makePlace(2L, itin, "b", "B", "12:30", null,   2);

        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(TRIP_ID))
                .thenReturn(List.of(before, after));
        when(placeRepository.findByGooglePlaceId(any())).thenReturn(Optional.empty());

        List<GapInfo> gaps = service.detectGaps(TRIP_ID);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).getTransportMode()).isEqualTo(TransportMode.WALK);
    }

    @Test
    @DisplayName("mode 직접 지정하면 trip 조회 없이 해당 모드 사용")
    void detectGap_explicitMode_usesThatMode() {
        Trip trip = makeTrip(TransportMode.WALK);
        Itinerary itin = makeItinerary(trip);

        TripPlace before = makePlace(1L, itin, "a", "A", "10:00", "11:00", 1);
        TripPlace after  = makePlace(2L, itin, "b", "B", "12:30", null,   2);

        // mode 직접 지정 → tripRepository 호출 없어야 함
        when(tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(TRIP_ID))
                .thenReturn(List.of(before, after));
        when(placeRepository.findByGooglePlaceId(any())).thenReturn(Optional.empty());

        List<GapInfo> gaps = service.detectGaps(TRIP_ID, TransportMode.CAR);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).getTransportMode()).isEqualTo(TransportMode.CAR);
        verify(tripRepository, never()).findById(any());
    }

    @Test
    @DisplayName("CAR 모드는 이동 속도가 빠르므로 WALK 대비 travelMinutes 더 짧음")
    void detectGap_carFasterThanWalk() {
        // 두 장소를 약 5km 떨어진 서울 내 지점으로 설정
        Trip tripWalk = makeTrip(TransportMode.WALK);
        Trip tripCar  = makeTrip(TransportMode.CAR);

        Itinerary iWalk = makeItinerary(tripWalk);
        Itinerary iCar  = Itinerary.builder().itineraryId(20L).trip(tripCar)
                .day(1).date(TODAY).build();

        TripPlace bW = makePlace(1L, iWalk, "a", "A", "10:00", "11:00", 1);
        TripPlace aW = makePlace(2L, iWalk, "b", "B", "13:00", null,   2);
        TripPlace bC = makePlace(3L, iCar,  "a", "A", "10:00", "11:00", 1);
        TripPlace aC = makePlace(4L, iCar,  "b", "B", "13:00", null,   2);

        Place pA = makePlaceEntity(37.5665, 126.9780); // 서울 시청
        Place pB = makePlaceEntity(37.5172, 127.0473); // 강남역 (~10km)

        when(tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(TRIP_ID))
                .thenReturn(List.of(bW, aW))
                .thenReturn(List.of(bC, aC));
        when(placeRepository.findByGooglePlaceId("a")).thenReturn(Optional.of(pA));
        when(placeRepository.findByGooglePlaceId("b")).thenReturn(Optional.of(pB));

        List<GapInfo> walkGaps = service.detectGaps(TRIP_ID, TransportMode.WALK);
        List<GapInfo> carGaps  = service.detectGaps(TRIP_ID, TransportMode.CAR);

        assertThat(walkGaps).hasSize(1);
        assertThat(carGaps).hasSize(1);
        assertThat(carGaps.get(0).getEstimatedTravelMinutes())
                .isLessThan(walkGaps.get(0).getEstimatedTravelMinutes());
        assertThat(carGaps.get(0).getAvailableMinutes())
                .isGreaterThan(walkGaps.get(0).getAvailableMinutes());
    }

    @Test
    @DisplayName("일정이 1개 이하이면 갭 없음")
    void detectGap_singlePlan_noGap() {
        Trip trip = makeTrip(TransportMode.WALK);
        Itinerary itin = makeItinerary(trip);
        TripPlace only = makePlace(1L, itin, "a", "A", "10:00", "12:00", 1);

        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(TRIP_ID))
                .thenReturn(List.of(only));

        assertThat(service.detectGaps(TRIP_ID)).isEmpty();
    }

    @Test
    @DisplayName("computeDirectTravelMinutes — place가 null이면 0 반환")
    void computeTravel_nullPlace_returnsZero() {
        assertThat(service.computeDirectTravelMinutes(null, null, TransportMode.WALK)).isZero();
    }
}
