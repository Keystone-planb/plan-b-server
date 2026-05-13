package com.planb.planb_backend.domain.trip.service;

import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.external.PlaceAnalysisService;
import com.planb.planb_backend.domain.trip.dto.*;
import com.planb.planb_backend.domain.trip.entity.Itinerary;
import com.planb.planb_backend.domain.trip.entity.TransportMode;
import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.ItineraryRepository;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.trip.repository.TripRepository;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripService {

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceRepository placeRepository;
    private final PlaceAnalysisService placeAnalysisService;

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
     * GET /api/trips — 내 여행 목록 조회 (status 필터)
     * status: UPCOMING / PAST / ALL
     */
    public List<TripListResponse> getMyTrips(String email, String status) {
        User user = findUser(email);
        LocalDate today = LocalDate.now();

        return tripRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .filter(trip -> switch (status.toUpperCase()) {
                    case "UPCOMING" -> today.isBefore(trip.getStartDate());
                    case "PAST"     -> today.isAfter(trip.getEndDate());
                    default         -> true;
                })
                .map(TripListResponse::from)
                .toList();
    }

    /**
     * GET /api/trips/{id} — 여행 상세 조회
     * 일정에 포함된 모든 placeId 기반으로 DB에서 좌표를 배치 조회해 응답에 포함
     */
    public TripDetailResponse getTripDetail(String email, Long tripId) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);

        // 모든 일정 장소의 googlePlaceId 수집
        List<String> placeIds = trip.getItineraries().stream()
                .flatMap(it -> it.getPlaces().stream())
                .map(TripPlace::getPlaceId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());

        // DB에서 좌표 배치 조회 → coordMap 빌드
        Map<String, double[]> coordMap = new HashMap<>();
        if (!placeIds.isEmpty()) {
            placeRepository.findAllByGooglePlaceIdIn(placeIds).forEach(p -> {
                if (p.getLatitude() != null && p.getLongitude() != null) {
                    coordMap.put(p.getGooglePlaceId(), new double[]{p.getLatitude(), p.getLongitude()});
                }
            });
        }

        return TripDetailResponse.from(trip, coordMap);
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
     * DELETE /api/trips/{id} — 여행 계획 삭제
     */
    @Transactional
    public void deleteTrip(String email, Long tripId) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);
        tripRepository.delete(trip);
    }

    /**
     * POST /api/trips/{id}/days/{day}/locations — 특정 일차에 장소 추가
     * visitTime/endTime이 있으면 해당 일차의 기존 일정과 시간대 겹침 검증
     */
    @Transactional
    public AddLocationResponse addLocation(String email, Long tripId, int day, AddLocationRequest request) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);

        Itinerary itinerary = itineraryRepository.findByTripAndDay(trip, day)
                .orElseThrow(() -> new IllegalArgumentException(day + "일차 일정을 찾을 수 없습니다."));

        // 시간 겹침 검증
        validateTimeOverlap(itinerary, request.getVisitTime(), request.getEndTime(), null);

        int nextOrder = itinerary.getPlaces().size() + 1;

        TripPlace tripPlace = TripPlace.builder()
                .itinerary(itinerary)
                .placeId(request.getPlaceId())
                .name(request.getName())
                .visitTime(request.getVisitTime())
                .endTime(request.getEndTime())
                .visitOrder(nextOrder)
                .memo(request.getMemo())
                .build();

        AddLocationResponse saved = AddLocationResponse.from(tripPlaceRepository.save(tripPlace));

        // 좌표 비동기 저장 — 틈새 추천 등 좌표 의존 기능을 위해 등록 즉시 좌표 확보
        // try-catch: 풀 초과 등 예외가 발생해도 addLocation 트랜잭션은 반드시 커밋
        if (request.getPlaceId() != null && !request.getPlaceId().isBlank()) {
            try {
                placeAnalysisService.ensureCoordinatesAsync(request.getPlaceId());
            } catch (Exception e) {
                log.warn("[addLocation] 좌표 비동기 저장 태스크 제출 실패 (placeId={}): {}", request.getPlaceId(), e.getMessage());
            }
        }

        return saved;
    }

    /**
     * DELETE /api/plans/{planId} — 일정 장소(TripPlace) 단건 삭제
     * 소유자 검증 후 물리적 삭제
     * Itinerary → TripPlace: orphanRemoval = true 이므로 repository.delete() 로 즉시 반영됨
     */
    @Transactional
    public void removeTripPlace(String email, Long tripPlaceId) {
        TripPlace tripPlace = tripPlaceRepository.findByIdAndUserEmail(tripPlaceId, email)
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없거나 접근 권한이 없습니다."));
        tripPlaceRepository.delete(tripPlace);
    }

    /**
     * POST /api/plans/{planId}/replace — 일정 장소 PLAN B 대체
     * placeId/name 교체, visitTime/endTime null 초기화, memo는 유지
     */
    @Transactional
    public void replaceTripPlace(String email, Long tripPlaceId, String newGooglePlaceId, String newPlaceName) {
        TripPlace tripPlace = tripPlaceRepository.findByIdAndUserEmail(tripPlaceId, email)
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없거나 접근 권한이 없습니다."));
        tripPlace.replace(newGooglePlaceId, newPlaceName);
    }

    /**
     * PATCH /api/plans/{planId}/schedule — 장소는 그대로, 시간/메모만 수정
     * visitTime/endTime이 있으면 해당 일차의 기존 일정과 시간대 겹침 검증 (자기 자신 제외)
     */
    @Transactional
    public AddLocationResponse updateTripPlaceSchedule(String email, Long tripPlaceId, UpdateScheduleRequest request) {
        TripPlace tripPlace = tripPlaceRepository.findByIdAndUserEmail(tripPlaceId, email)
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없거나 접근 권한이 없습니다."));

        // 시간 겹침 검증 (자기 자신은 제외)
        validateTimeOverlap(tripPlace.getItinerary(), request.getVisitTime(), request.getEndTime(), tripPlaceId);

        tripPlace.updateSchedule(request.getVisitTime(), request.getEndTime(), request.getMemo());
        return AddLocationResponse.from(tripPlace);
    }

    /**
     * GET /api/trips/{id}/transport-mode — 이동 수단 조회
     */
    public TransportMode getTransportMode(String email, Long tripId) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);
        return trip.getTransportMode();
    }

    /**
     * PATCH /api/trips/{id}/transport-mode — 이동 수단 수정
     */
    @Transactional
    public void updateTransportMode(String email, Long tripId, TransportMode mode) {
        User user = findUser(email);
        Trip trip = findTripByOwner(tripId, user);
        trip.updateTransportMode(mode);
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

    /**
     * 시간대 겹침 검증
     * - visitTime 또는 endTime 중 하나라도 null이면 스킵 (시간 미설정 허용)
     * - excludeTripPlaceId: 자기 자신 수정 시 자신을 비교 대상에서 제외
     * - 겹침 조건: newStart < existEnd AND existStart < newEnd
     */
    private void validateTimeOverlap(Itinerary itinerary, String visitTime, String endTime, Long excludeTripPlaceId) {
        if (visitTime == null || endTime == null) return;

        LocalTime newStart = LocalTime.parse(visitTime);
        LocalTime newEnd   = LocalTime.parse(endTime);

        if (!newEnd.isAfter(newStart)) {
            throw new IllegalArgumentException("종료 시간(" + endTime + ")은 시작 시간(" + visitTime + ")보다 늦어야 합니다.");
        }

        for (TripPlace existing : itinerary.getPlaces()) {
            if (excludeTripPlaceId != null && existing.getTripPlaceId().equals(excludeTripPlaceId)) continue;
            if (existing.getVisitTime() == null || existing.getEndTime() == null) continue;

            LocalTime existStart = LocalTime.parse(existing.getVisitTime());
            LocalTime existEnd   = LocalTime.parse(existing.getEndTime());

            if (newStart.isBefore(existEnd) && existStart.isBefore(newEnd)) {
                throw new IllegalArgumentException(
                    String.format("'%s'의 시간대(%s ~ %s)와 겹칩니다. 다른 시간대를 선택해주세요.",
                        existing.getName(), existing.getVisitTime(), existing.getEndTime())
                );
            }
        }
    }
}
