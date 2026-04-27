package com.planb.planb_backend.domain.trip.service;

import com.planb.planb_backend.domain.trip.dto.*;
import com.planb.planb_backend.domain.trip.entity.Itinerary;
import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.ItineraryRepository;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.trip.repository.TripRepository;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import com.planb.planb_backend.domain.trip.dto.TripListResponse;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripService {

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;

    /**
     * POST /api/trips — 여행 계획 생성
     * 시작일~종료일 기준으로 Itinerary(일차)를 자동 생성
     */
    @Transactional
    public TripSummaryResponse createTrip(String email, CreateTripRequest request) {
        User user = findUser(email);

        Trip trip = Trip.builder()
                .title(request.getTitle())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .user(user)
                .travelStyles(new ArrayList<>(request.getTravelStyles()))
                .build();

        // 날짜 수만큼 Itinerary 자동 생성
        long totalDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        for (int i = 0; i < totalDays; i++) {
            Itinerary itinerary = Itinerary.builder()
                    .trip(trip)
                    .day(i + 1)
                    .date(request.getStartDate().plusDays(i))
                    .build();
            trip.getItineraries().add(itinerary);
        }

        return TripSummaryResponse.from(tripRepository.save(trip));
    }

    /**
     * GET /api/trips — 내 여행 목록 조회 (status 필터 지원)
     * status: UPCOMING(예정) / PAST(지난) / ALL(전체)
     */
    public List<TripListResponse> getMyTrips(String email, String status) {
        User user = findUser(email);
        LocalDate today = LocalDate.now();

        return tripRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .filter(trip -> switch (status.toUpperCase()) {
                    case "UPCOMING" -> today.isBefore(trip.getStartDate());
                    case "PAST"     -> today.isAfter(trip.getEndDate());
                    default         -> true; // ALL
                })
                .map(TripListResponse::from)
                .toList();
    }

    /**
     * GET /api/trips/{id} — 특정 여행 상세 조회
     */
    public TripDetailResponse getTripDetail(String email, Long tripId) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);
        return TripDetailResponse.from(trip);
    }

    /**
     * PATCH /api/trips/{id} — 여행 정보 부분 수정
     */
    @Transactional
    public TripSummaryResponse updateTrip(String email, Long tripId, UpdateTripRequest request) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);
        trip.update(request.getTitle(), request.getStartDate(), request.getEndDate(), request.getTravelStyles());
        return TripSummaryResponse.from(trip);
    }

    /**
     * DELETE /api/trips/{id} — 여행 계획 삭제 (Itinerary, TripPlace 연쇄 삭제)
     */
    @Transactional
    public void deleteTrip(String email, Long tripId) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);
        tripRepository.delete(trip);
    }

    /**
     * POST /api/trips/{id}/days/{day}/locations — 특정 일차에 장소 추가
     */
    @Transactional
    public AddLocationResponse addLocation(String email, Long tripId, int day, AddLocationRequest request) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);

        Itinerary itinerary = itineraryRepository.findByTripAndDay(trip, day)
                .orElseThrow(() -> new IllegalArgumentException(day + "일차 일정을 찾을 수 없습니다."));

        int nextOrder = itinerary.getPlaces().size() + 1;

        TripPlace tripPlace = TripPlace.builder()
                .itinerary(itinerary)
                .placeId(request.getPlaceId())
                .name(request.getName())
                .visitTime(request.getVisitTime())
                .visitOrder(nextOrder)
                .memo(request.getMemo())
                .build();

        return AddLocationResponse.from(tripPlaceRepository.save(tripPlace));
    }

    // ── private 헬퍼 ────────────────────────────────────────────────

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    private Trip findTripByOwner(Long tripId, User user) {
        return tripRepository.findByTripIdAndUser(tripId, user)
                .orElseThrow(() -> new IllegalArgumentException("여행을 찾을 수 없거나 접근 권한이 없습니다."));
    }
}
