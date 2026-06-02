package com.planb.planb_backend.admin;

import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.preference.repository.UserPreferenceRepository;
import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.trip.repository.TripRepository;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.RefreshTokenRepository;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository              userRepository;
    private final TripRepository              tripRepository;
    private final TripPlaceRepository         tripPlaceRepository;
    private final RefreshTokenRepository      refreshTokenRepository;
    private final UserPreferenceRepository    userPreferenceRepository;
    private final PlaceRepository             placeRepository;
    private final AdminNotificationRepository adminNotificationRepository;
    private final AdminEmailAuthRepository    adminEmailAuthRepository;

    // ── 사용자 목록 ─────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<AdminUserDto> getAllUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream().map(AdminUserDto::from).toList();
    }

    // ── 사용자 강제 삭제 ────────────────────────────────────────────────────
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        log.info("[Admin] 사용자 삭제 시작: userId={}, email={}", userId, user.getEmail());

        adminNotificationRepository.deleteByUserId(userId);
        adminEmailAuthRepository.deleteByEmail(user.getEmail());
        refreshTokenRepository.findByUser(user).ifPresent(refreshTokenRepository::delete);
        userPreferenceRepository.deleteAll(userPreferenceRepository.findByUserId(userId));

        List<Trip> trips = tripRepository.findByUserOrderByCreatedAtDesc(user);
        tripRepository.deleteAll(trips);
        userRepository.delete(user);

        log.info("[Admin] 사용자 삭제 완료: userId={}", userId);
    }

    // ── 특정 사용자의 여행 목록 ─────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<AdminTripDto> getTripsByUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));
        return tripRepository.findByUserOrderByCreatedAtDesc(user)
                .stream().map(AdminTripDto::from).toList();
    }

    // ── 여행 강제 삭제 ──────────────────────────────────────────────────────
    @Transactional
    public void deleteTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("여행을 찾을 수 없습니다: " + tripId));

        log.info("[Admin] 여행 삭제 시작: tripId={}, title={}", tripId, trip.getTitle());

        List<Long> placeIds = tripPlaceRepository.findByTripId(tripId)
                .stream().map(TripPlace::getTripPlaceId).toList();
        if (!placeIds.isEmpty()) {
            adminNotificationRepository.deleteByPlanIdIn(placeIds);
        }

        tripRepository.delete(trip);
        log.info("[Admin] 여행 삭제 완료: tripId={}", tripId);
    }

    // ── TripPlace 단건 삭제 ─────────────────────────────────────────────────
    @Transactional
    public void deleteTripPlace(Long tripPlaceId) {
        TripPlace tripPlace = tripPlaceRepository.findById(tripPlaceId)
                .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다: " + tripPlaceId));

        log.info("[Admin] 장소 삭제 시작: tripPlaceId={}, name={}", tripPlaceId, tripPlace.getName());

        adminNotificationRepository.deleteByPlanIdIn(List.of(tripPlaceId));
        tripPlaceRepository.delete(tripPlace);

        log.info("[Admin] 장소 삭제 완료: tripPlaceId={}", tripPlaceId);
    }

    // ── 특정 여행의 장소 목록 (Place 배치 조인) ─────────────────────────────
    /**
     * N+1 방지: TripPlace 전체 조회 후 googlePlaceId 목록으로 Place를 배치 로드,
     * Map으로 변환해 DTO 생성 시 단건 조회 없이 O(1) 접근
     */
    @Transactional(readOnly = true)
    public List<AdminTripPlaceDto> getPlacesByTrip(Long tripId) {
        List<TripPlace> tripPlaces = tripPlaceRepository.findByTripId(tripId);

        // 배치 Place 로드
        List<String> googleIds = tripPlaces.stream()
                .map(TripPlace::getPlaceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, Place> placeMap = placeRepository.findAllByGooglePlaceIdIn(googleIds)
                .stream()
                .collect(Collectors.toMap(Place::getGooglePlaceId, p -> p));

        return tripPlaces.stream()
                .map(tp -> AdminTripPlaceDto.from(tp, placeMap.get(tp.getPlaceId())))
                .toList();
    }

    // ── DB 장소 전체 목록 (장소 관리 탭) ───────────────────────────────────
    @Transactional(readOnly = true)
    public List<AdminPlaceDto> getAllPlaces() {
        return placeRepository.findAll(Sort.by(Sort.Direction.DESC, "lastSyncedAt"))
                .stream().map(AdminPlaceDto::from).toList();
    }

    // ════════════════════════════════════════════════════════════════════════
    // DTOs
    // ════════════════════════════════════════════════════════════════════════

    public record AdminUserDto(
            Long id, String email, String nickname,
            String role, String provider, String status, String createdAt
    ) {
        static AdminUserDto from(User u) {
            return new AdminUserDto(
                    u.getId(), u.getEmail(), u.getNickname(),
                    u.getRole().name(), u.getProvider(), u.getStatus(),
                    u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
        }
    }

    public record AdminTripDto(
            Long tripId, String title, String startDate, String endDate,
            String transportMode, String travelStyles, String createdAt
    ) {
        static AdminTripDto from(Trip t) {
            String styles = t.getTravelStyles().stream()
                    .map(Enum::name).reduce((a, b) -> a + ", " + b).orElse("—");
            return new AdminTripDto(
                    t.getTripId(), t.getTitle(),
                    t.getStartDate().toString(), t.getEndDate().toString(),
                    t.getTransportMode() != null ? t.getTransportMode().name() : null,
                    styles,
                    t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        }
    }

    public record AdminTripPlaceDto(
            // ── TripPlace 직접 필드 ──────────────────────────────────────
            Long   tripPlaceId,
            String googlePlaceId,   // TripPlace.placeId (Google Place ID)
            String name,
            int    day,
            String date,
            String visitTime,
            String endTime,
            int    visitOrder,
            String memo,            // TripPlace.memo (단순 메모 문자열)
            String source,
            String transportMode,   // TripPlace 개별 이동수단 (null이면 Trip 기본값 폴백)
            // ── Place 조인 필드 (Place DB에 없으면 null) ─────────────────
            Double latitude,
            Double longitude,
            String category,
            String placeType,       // PlaceType enum
            String space,           // Space enum
            String mood,            // Mood enum
            Double rating,
            Integer userRatingsTotal,
            String openingHours,    // JSONB raw string
            String reviewData,      // JSONB raw string (AI 분석 리뷰 전체)
            String analysisStatus   // COMPLETE / PENDING (Place 분석 완료 여부)
    ) {
        static AdminTripPlaceDto from(TripPlace tp, Place place) {
            boolean analyzed = place != null
                    && place.getType()  != null
                    && place.getSpace() != null
                    && place.getMood()  != null;

            return new AdminTripPlaceDto(
                    tp.getTripPlaceId(),
                    tp.getPlaceId(),
                    tp.getName(),
                    tp.getItinerary().getDay(),
                    tp.getItinerary().getDate().toString(),
                    tp.getVisitTime(),
                    tp.getEndTime(),
                    tp.getVisitOrder(),
                    tp.getMemo(),
                    tp.getSource()        != null ? tp.getSource().name()        : "NORMAL",
                    tp.getTransportMode() != null ? tp.getTransportMode().name() : null,
                    // Place fields
                    place != null ? place.getLatitude()        : null,
                    place != null ? place.getLongitude()       : null,
                    place != null ? place.getCategory()        : null,
                    place != null && place.getType()  != null ? place.getType().name()  : null,
                    place != null && place.getSpace() != null ? place.getSpace().name() : null,
                    place != null && place.getMood()  != null ? place.getMood().name()  : null,
                    place != null ? place.getRating()          : null,
                    place != null ? place.getUserRatingsTotal(): null,
                    place != null ? place.getOpeningHours()    : null,
                    place != null ? place.getReviewData()      : null,
                    analyzed ? "COMPLETE" : "PENDING"
            );
        }
    }

    public record AdminPlaceDto(
            Long id, String googlePlaceId, String name, String address,
            String type, String space, String mood,
            Double rating, Integer userRatingsTotal,
            String lastSyncedAt, String analysisStatus
    ) {
        static AdminPlaceDto from(Place p) {
            boolean analyzed = p.getType() != null && p.getSpace() != null && p.getMood() != null;
            return new AdminPlaceDto(
                    p.getId(), p.getGooglePlaceId(), p.getName(), p.getAddress(),
                    p.getType()  != null ? p.getType().name()  : null,
                    p.getSpace() != null ? p.getSpace().name() : null,
                    p.getMood()  != null ? p.getMood().name()  : null,
                    p.getRating(), p.getUserRatingsTotal(),
                    p.getLastSyncedAt() != null ? p.getLastSyncedAt().toString() : null,
                    analyzed ? "COMPLETE" : "PENDING");
        }
    }
}
