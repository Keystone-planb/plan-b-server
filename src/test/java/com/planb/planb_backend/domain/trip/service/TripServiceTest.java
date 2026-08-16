package com.planb.planb_backend.domain.trip.service;

import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.external.GooglePlaceApiService;
import com.planb.planb_backend.domain.place.service.external.PlaceAnalysisService;
import com.planb.planb_backend.domain.trip.dto.TripListCacheEntry;
import com.planb.planb_backend.domain.trip.entity.Itinerary;
import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.trip.repository.ItineraryRepository;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.trip.repository.TripRepository;
import com.planb.planb_backend.domain.user.entity.Role;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 2026-08-16 부하테스트에서 발견한 N+1 회귀 방지용 테스트.
 * getMyTrips()가 itinerary.getPlaces().size()로 이티너리마다 lazy 쿼리를 내던 걸
 * countByItineraryIds() 배치조회 1회로 바꿨는데, 누가 실수로 다시 개별조회로
 * 되돌려도 여기서 바로 잡히도록 "배치조회가 정확히 1번만 호출되는지"를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TripServiceTest {

    @Mock private TripRepository        tripRepository;
    @Mock private UserRepository        userRepository;
    @Mock private ItineraryRepository   itineraryRepository;
    @Mock private TripPlaceRepository   tripPlaceRepository;
    @Mock private PlaceRepository       placeRepository;
    @Mock private PlaceAnalysisService  placeAnalysisService;
    @Mock private GooglePlaceApiService googlePlaceApiService;
    @Mock private CacheManager          cacheManager;

    private TripService tripService;

    private User user;

    @BeforeEach
    void setUp() {
        // @InjectMocks 대신 직접 생성 — 필드 순서에 의존하지 않고 명시적으로 주입
        tripService = new TripService(
                tripRepository, userRepository, itineraryRepository, tripPlaceRepository,
                placeRepository, placeAnalysisService, googlePlaceApiService, cacheManager);

        user = User.builder()
                .email("test@planb.com")
                .nickname("테스터")
                .provider("local")
                .role(Role.USER)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("getMyTrips: 이티너리 개수와 무관하게 countByItineraryIds가 정확히 1번만 호출됨 (N+1 회귀 방지)")
    void getMyTrips_batchesPlaceCountQuery() {
        when(userRepository.findByEmail("test@planb.com")).thenReturn(java.util.Optional.of(user));

        // 여행 2개, 각각 이티너리 2개씩 — 총 4개 이티너리
        Trip trip1 = tripWithItineraries(1L, 2);
        Trip trip2 = tripWithItineraries(2L, 2);
        when(tripRepository.findByUserWithItineraries(user)).thenReturn(List.of(trip1, trip2));

        when(tripPlaceRepository.countByItineraryIds(anyList())).thenReturn(List.of());

        TripListCacheEntry result = tripService.getMyTrips("test@planb.com", "ALL");

        assertThat(result.getTrips()).hasSize(2);

        // 이티너리가 4개나 있어도 배치조회는 딱 1번 — 이게 깨지면 N+1이 부활한 것
        verify(tripPlaceRepository, times(1)).countByItineraryIds(anyList());
    }

    @Test
    @DisplayName("getMyTrips: status=UPCOMING이면 과거 여행은 결과에서 제외")
    void getMyTrips_filtersUpcomingOnly() {
        when(userRepository.findByEmail("test@planb.com")).thenReturn(java.util.Optional.of(user));

        Trip pastTrip = Trip.builder()
                .tripId(1L)
                .title("지난 여행")
                .startDate(LocalDate.now().minusDays(10))
                .endDate(LocalDate.now().minusDays(5))
                .user(user)
                .build();
        ReflectionTestUtils.setField(pastTrip, "itineraries", new ArrayList<Itinerary>());

        Trip upcomingTrip = Trip.builder()
                .tripId(2L)
                .title("다가올 여행")
                .startDate(LocalDate.now().plusDays(5))
                .endDate(LocalDate.now().plusDays(10))
                .user(user)
                .build();
        ReflectionTestUtils.setField(upcomingTrip, "itineraries", new ArrayList<Itinerary>());

        when(tripRepository.findByUserWithItineraries(user)).thenReturn(List.of(pastTrip, upcomingTrip));
        when(tripPlaceRepository.countByItineraryIds(anyList())).thenReturn(List.of());

        TripListCacheEntry result = tripService.getMyTrips("test@planb.com", "UPCOMING");

        assertThat(result.getTrips()).hasSize(1);
        assertThat(result.getTrips().get(0).getTitle()).isEqualTo("다가올 여행");
    }

    private Trip tripWithItineraries(Long tripId, int itineraryCount) {
        Trip trip = Trip.builder()
                .tripId(tripId)
                .title("여행 " + tripId)
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(3))
                .user(user)
                .build();

        List<Itinerary> itineraries = new ArrayList<>();
        for (int i = 0; i < itineraryCount; i++) {
            Itinerary itinerary = Itinerary.builder()
                    .trip(trip)
                    .day(i + 1)
                    .date(LocalDate.now().plusDays(i))
                    .build();
            ReflectionTestUtils.setField(itinerary, "itineraryId", tripId * 100 + i);
            itineraries.add(itinerary);
        }
        ReflectionTestUtils.setField(trip, "itineraries", itineraries);
        return trip;
    }
}
