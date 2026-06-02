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

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository             userRepository;
    private final TripRepository             tripRepository;
    private final TripPlaceRepository        tripPlaceRepository;
    private final RefreshTokenRepository     refreshTokenRepository;
    private final UserPreferenceRepository   userPreferenceRepository;
    private final PlaceRepository            placeRepository;
    private final AdminNotificationRepository adminNotificationRepository;
    private final AdminEmailAuthRepository   adminEmailAuthRepository;

    // ── 사용자 목록 ─────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<AdminUserDto> getAllUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(AdminUserDto::from)
                .toList();
    }

    // ── 사용자 강제 삭제 ────────────────────────────────────────────────────
    /**
     * FK 역방향 안전 삭제 순서:
     * 1. Notification (userId) — planId 기준 알림도 함께 포함
     * 2. EmailAuth (email)
     * 3. RefreshToken (user_id FK → Users 삭제 전 반드시 먼저)
     * 4. UserPreference (userId)
     * 5. Trip → JPA CascadeType.ALL → Itinerary → TripPlace → TripPlaceMemo 자동
     * 6. User
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        log.info("[Admin] 사용자 삭제 시작: userId={}, email={}", userId, user.getEmail());

        // 1. 알림 삭제 (userId 기준 — 해당 유저의 모든 알림 포함)
        adminNotificationRepository.deleteByUserId(userId);

        // 2. 이메일 인증 기록 삭제
        adminEmailAuthRepository.deleteByEmail(user.getEmail());

        // 3. 리프레시 토큰 삭제 (user_id FK → User 삭제 전 필수)
        refreshTokenRepository.findByUser(user).ifPresent(refreshTokenRepository::delete);

        // 4. 유저 선호도 삭제
        userPreferenceRepository.deleteAll(userPreferenceRepository.findByUserId(userId));

        // 5. 여행 전체 삭제 (JPA Cascade: Trip → Itinerary → TripPlace → TripPlaceMemo)
        List<Trip> trips = tripRepository.findByUserOrderByCreatedAtDesc(user);
        tripRepository.deleteAll(trips);

        // 6. 유저 삭제
        userRepository.delete(user);
        log.info("[Admin] 사용자 삭제 완료: userId={}", userId);
    }

    // ── 특정 사용자의 여행 목록 ─────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<AdminTripDto> getTripsByUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));
        return tripRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(AdminTripDto::from)
                .toList();
    }

    // ── 여행 강제 삭제 ──────────────────────────────────────────────────────
    /**
     * 안전 삭제 순서:
     * 1. TripPlace ID 수집 → 해당 planId 알림 먼저 삭제 (User는 살아있으므로 userId 기준 X)
     * 2. Trip 삭제 → JPA Cascade: Itinerary → TripPlace → TripPlaceMemo
     */
    @Transactional
    public void deleteTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("여행을 찾을 수 없습니다: " + tripId));

        log.info("[Admin] 여행 삭제 시작: tripId={}, title={}", tripId, trip.getTitle());

        // 1. 이 여행의 TripPlace ID 목록 수집 → 연결된 알림 삭제
        List<Long> placeIds = tripPlaceRepository.findByTripId(tripId)
                .stream()
                .map(TripPlace::getTripPlaceId)
                .toList();
        if (!placeIds.isEmpty()) {
            adminNotificationRepository.deleteByPlanIdIn(placeIds);
        }

        // 2. Trip 삭제 (JPA Cascade ALL: trip_travel_styles / Itinerary / TripPlace / TripPlaceMemo)
        tripRepository.delete(trip);
        log.info("[Admin] 여행 삭제 완료: tripId={}", tripId);
    }

    // ── TripPlace 단건 삭제 ─────────────────────────────────────────────────
    /**
     * 안전 삭제 순서:
     * 1. Notification(planId = tripPlaceId) 먼저 삭제
     * 2. TripPlace 삭제 → JPA CascadeType.ALL + orphanRemoval → TripPlaceMemo 자동 삭제
     */
    @Transactional
    public void deleteTripPlace(Long tripPlaceId) {
        TripPlace tripPlace = tripPlaceRepository.findById(tripPlaceId)
                .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다: " + tripPlaceId));

        log.info("[Admin] 장소 삭제 시작: tripPlaceId={}, name={}", tripPlaceId, tripPlace.getName());

        // 1. 이 장소를 참조하는 알림 먼저 삭제
        adminNotificationRepository.deleteByPlanIdIn(List.of(tripPlaceId));

        // 2. TripPlace 삭제 (JPA Cascade ALL + orphanRemoval → TripPlaceMemo 자동 삭제)
        tripPlaceRepository.delete(tripPlace);
        log.info("[Admin] 장소 삭제 완료: tripPlaceId={}", tripPlaceId);
    }

    // ── 특정 여행의 장소 목록 ────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<AdminTripPlaceDto> getPlacesByTrip(Long tripId) {
        return tripPlaceRepository.findByTripId(tripId)
                .stream()
                .map(AdminTripPlaceDto::from)
                .toList();
    }

    // ── DB 장소 전체 목록 (기존 장소 관리 탭) ──────────────────────────────
    @Transactional(readOnly = true)
    public List<AdminPlaceDto> getAllPlaces() {
        return placeRepository.findAll(Sort.by(Sort.Direction.DESC, "lastSyncedAt"))
                .stream()
                .map(AdminPlaceDto::from)
                .toList();
    }

    // ════════════════════════════════════════════════════════════════════════
    // DTOs
    // ════════════════════════════════════════════════════════════════════════

    public record AdminUserDto(
            Long id,
            String email,
            String nickname,
            String role,
            String provider,
            String status,
            String createdAt
    ) {
        static AdminUserDto from(User u) {
            return new AdminUserDto(
                    u.getId(), u.getEmail(), u.getNickname(),
                    u.getRole().name(), u.getProvider(), u.getStatus(),
                    u.getCreatedAt() != null ? u.getCreatedAt().toString() : null
            );
        }
    }

    public record AdminTripDto(
            Long tripId,
            String title,
            String startDate,
            String endDate,
            String transportMode,
            String travelStyles,
            String createdAt
    ) {
        static AdminTripDto from(Trip t) {
            String styles = t.getTravelStyles().stream()
                    .map(Enum::name)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("—");
            return new AdminTripDto(
                    t.getTripId(), t.getTitle(),
                    t.getStartDate().toString(), t.getEndDate().toString(),
                    t.getTransportMode() != null ? t.getTransportMode().name() : null,
                    styles,
                    t.getCreatedAt() != null ? t.getCreatedAt().toString() : null
            );
        }
    }

    public record AdminTripPlaceDto(
            Long tripPlaceId,
            String googlePlaceId,
            String name,
            int day,
            String date,
            String visitTime,
            String endTime,
            String source,
            int visitOrder
    ) {
        static AdminTripPlaceDto from(TripPlace tp) {
            return new AdminTripPlaceDto(
                    tp.getTripPlaceId(),
                    tp.getPlaceId(),
                    tp.getName(),
                    tp.getItinerary().getDay(),
                    tp.getItinerary().getDate().toString(),
                    tp.getVisitTime(),
                    tp.getEndTime(),
                    tp.getSource() != null ? tp.getSource().name() : "NORMAL",
                    tp.getVisitOrder()
            );
        }
    }

    public record AdminPlaceDto(
            Long id,
            String googlePlaceId,
            String name,
            String address,
            String type,
            String space,
            String mood,
            Double rating,
            Integer userRatingsTotal,
            String lastSyncedAt,
            String analysisStatus
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
                    analyzed ? "COMPLETE" : "PENDING"
            );
        }
    }
}
