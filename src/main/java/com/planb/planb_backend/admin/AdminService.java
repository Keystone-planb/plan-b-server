package com.planb.planb_backend.admin;

import com.planb.planb_backend.domain.bookmark.entity.Bookmark;
import com.planb.planb_backend.domain.bookmark.repository.BookmarkRepository;
import com.planb.planb_backend.domain.notification.entity.Notification;
import com.planb.planb_backend.domain.notification.scheduler.WeatherScheduler;
import com.planb.planb_backend.domain.notification.service.ExpoPushService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final BookmarkRepository          bookmarkRepository;
    private final AdminNotificationRepository adminNotificationRepository;
    private final AdminEmailAuthRepository    adminEmailAuthRepository;
    private final ExpoPushService             expoPushService;
    private final WeatherScheduler            weatherScheduler;

    // ── 사용자 목록 ─────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public AdminPageResponse<AdminUserDto> getAllUsers(int page, int size) {
        Page<User> userPage = userRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<AdminUserDto> content = userPage.getContent().stream()
                .map(AdminUserDto::from).toList();
        return new AdminPageResponse<>(content, userPage.getTotalElements(),
                userPage.getTotalPages(), userPage.getNumber(), size);
    }

    // ── 사용자 강제 삭제 ────────────────────────────────────────────────────
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        log.info("[Admin] 사용자 삭제 시작: userId={}, email={}", userId, user.getEmail());

        adminNotificationRepository.deleteByUserId(userId);
        adminEmailAuthRepository.deleteByEmail(user.getEmail());
        refreshTokenRepository.deleteByUser(user);
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
    public AdminPageResponse<AdminPlaceDto> getAllPlaces(int page, int size) {
        Page<Place> placePage = placeRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastSyncedAt")));
        List<AdminPlaceDto> content = placePage.getContent().stream()
                .map(AdminPlaceDto::from).toList();
        return new AdminPageResponse<>(content, placePage.getTotalElements(),
                placePage.getTotalPages(), placePage.getNumber(), size);
    }

    // ── 특정 사용자의 무드 선호도 조회 ────────────────────────────────────
    @Transactional(readOnly = true)
    public List<AdminMoodPreferenceDto> getUserMoodPreferences(Long userId) {
        return userPreferenceRepository.findByUserId(userId)
                .stream()
                .sorted(Comparator.comparingDouble(up -> -up.getScore()))
                .map(up -> new AdminMoodPreferenceDto(up.getMood().name(), up.getScore()))
                .toList();
    }

    // ── 날씨 알림 전체 목록 (알림 관제 탭) ────────────────────────────────
    /**
     * N+1 방지:
     * 1) Notification 페이지 단위 조회
     * 2) userId 배치 → User 1회 IN 조회
     * 3) planId 배치 → TripPlace FETCH JOIN (itinerary, trip) 1회 조회
     */
    @Transactional(readOnly = true)
    public AdminPageResponse<AdminNotificationDto> getAllNotifications(int page, int size) {
        Page<Notification> notifPage = adminNotificationRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Notification> notifications = notifPage.getContent();

        // 배치 User 로드
        List<Long> userIds = notifications.stream()
                .map(Notification::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 배치 TripPlace 로드 (@Transactional 세션 내에서 lazy 로딩 허용)
        List<Long> planIds = notifications.stream()
                .map(Notification::getPlanId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TripPlace> tripPlaceMap = planIds.isEmpty()
                ? Map.of()
                : tripPlaceRepository.findAllById(planIds).stream()
                        .collect(Collectors.toMap(TripPlace::getTripPlaceId, tp -> tp));

        List<AdminNotificationDto> content = notifications.stream().map(n -> {
            User      user     = userMap.get(n.getUserId());
            TripPlace tp       = tripPlaceMap.get(n.getPlanId());
            String    tripTitle = null;
            try {
                if (tp != null) tripTitle = tp.getItinerary().getTrip().getTitle();
            } catch (Exception ignored) {}
            return AdminNotificationDto.from(n, user, tp, tripTitle);
        }).toList();

        return new AdminPageResponse<>(content, notifPage.getTotalElements(),
                notifPage.getTotalPages(), notifPage.getNumber(), size);
    }

    // ── 날씨 스케줄러 수동 실행 ────────────────────────────────────────────
    public void triggerWeatherScheduler() {
        log.info("[Admin] 날씨 스케줄러 수동 실행 요청");
        weatherScheduler.checkWeatherAndNotify();
        log.info("[Admin] 날씨 스케줄러 수동 실행 완료");
    }

    // ── 즐겨찾기 전체 목록 ──────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public AdminPageResponse<AdminBookmarkDto> getAllBookmarks(int page, int size) {
        Page<Bookmark> bookmarkPage = bookmarkRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Bookmark> bookmarks = bookmarkPage.getContent();

        List<Long> userIds = bookmarks.stream().map(Bookmark::getUserId).distinct().toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<AdminBookmarkDto> content = bookmarks.stream()
                .map(b -> AdminBookmarkDto.from(b, userMap.get(b.getUserId())))
                .toList();
        return new AdminPageResponse<>(content, bookmarkPage.getTotalElements(),
                bookmarkPage.getTotalPages(), bookmarkPage.getNumber(), size);
    }

    // ── 즐겨찾기 강제 삭제 ─────────────────────────────────────────────────
    @Transactional
    public void deleteBookmark(Long bookmarkId) {
        Bookmark bookmark = bookmarkRepository.findById(bookmarkId)
                .orElseThrow(() -> new IllegalArgumentException("즐겨찾기를 찾을 수 없습니다: " + bookmarkId));
        log.info("[Admin] 즐겨찾기 삭제: bookmarkId={}, userId={}, place={}", bookmarkId, bookmark.getUserId(), bookmark.getName());
        bookmarkRepository.delete(bookmark);
    }

    // ── 대시보드 요약 통계 ─────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public AdminStatsDto getStats() {
        long totalUsers         = userRepository.count();
        long totalTrips         = tripRepository.count();
        long totalPlaces        = placeRepository.count();
        long analyzedPlaces     = placeRepository.countByTypeIsNotNullAndSpaceIsNotNullAndMoodIsNotNull();
        long totalNotifications = adminNotificationRepository.count();
        long unsentNotifications= adminNotificationRepository.countByPushSentAtIsNull();
        long totalBookmarks     = bookmarkRepository.count();
        long totalPreferences   = userPreferenceRepository.count();
        return new AdminStatsDto(
                totalUsers, totalTrips,
                totalPlaces, analyzedPlaces,
                totalNotifications, unsentNotifications,
                totalBookmarks, totalPreferences);
    }

    // ── 시계열 통계 (데이터 수치 탭 라인 차트) ────────────────────────────
    @Transactional(readOnly = true)
    public AdminTimeSeriesDto getTimeSeries() {
        LocalDate today = LocalDate.now();
        // 14일 윈도우: 인덱스 0~6 = 이전 7일, 7~13 = 현재 7일
        LocalDateTime from14 = today.minusDays(13).atStartOfDay();

        Map<LocalDate, Long> userMap  = toDateMap(userRepository.countDailyNew(from14));
        Map<LocalDate, Long> tripMap  = toDateMap(tripRepository.countDailyNew(from14));
        Map<LocalDate, Long> placeMap = toDateMap(placeRepository.countDailyAnalyzed(from14));

        List<String> labels       = new ArrayList<>();
        List<Long>   dailyUsers   = new ArrayList<>();
        List<Long>   dailyTrips   = new ArrayList<>();
        List<Long>   dailyPlaces  = new ArrayList<>();

        for (int i = 13; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            labels.add(d.getMonthValue() + "/" + d.getDayOfMonth());
            dailyUsers.add(userMap.getOrDefault(d, 0L));
            dailyTrips.add(tripMap.getOrDefault(d, 0L));
            dailyPlaces.add(placeMap.getOrDefault(d, 0L));
        }

        // 현재 기간 = 뒤 7일(인덱스 7~13), 이전 기간 = 앞 7일(인덱스 0~6)
        long curUsers  = sumRange(dailyUsers,  7, 14);
        long prevUsers = sumRange(dailyUsers,  0, 7);
        long curTrips  = sumRange(dailyTrips,  7, 14);
        long prevTrips = sumRange(dailyTrips,  0, 7);
        long curPlaces = sumRange(dailyPlaces, 7, 14);
        long prevPlaces= sumRange(dailyPlaces, 0, 7);

        long totalPreferences = userPreferenceRepository.count();

        return new AdminTimeSeriesDto(
                labels, dailyUsers, dailyTrips, dailyPlaces,
                curUsers,  calcGrowth(prevUsers,  curUsers),
                curTrips,  calcGrowth(prevTrips,  curTrips),
                curPlaces, calcGrowth(prevPlaces, curPlaces),
                totalPreferences
        );
    }

    private Map<LocalDate, Long> toDateMap(List<Object[]> rows) {
        Map<LocalDate, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            LocalDate d;
            if (row[0] instanceof java.sql.Date sqlDate) {
                d = sqlDate.toLocalDate();
            } else if (row[0] instanceof LocalDate localDate) {
                d = localDate;
            } else {
                d = LocalDate.parse(row[0].toString());
            }
            long cnt = row[1] == null ? 0L : ((Number) row[1]).longValue();
            map.put(d, cnt);
        }
        return map;
    }

    private long sumRange(List<Long> list, int from, int to) {
        return list.subList(from, to).stream().mapToLong(Long::longValue).sum();
    }

    private double calcGrowth(long prev, long curr) {
        if (prev == 0) return curr > 0 ? 100.0 : 0.0;
        return Math.round((curr - prev) * 1000.0 / prev) / 10.0;
    }

    // ── 날씨 알림 수동 재발송 ──────────────────────────────────────────────
    @Transactional
    public void resendNotification(Long notificationId) {
        Notification n = adminNotificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다: " + notificationId));

        User user = userRepository.findById(n.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + n.getUserId()));

        if (user.getExpoPushToken() == null || user.getExpoPushToken().isBlank()) {
            throw new IllegalStateException("해당 사용자의 푸시 토큰이 없습니다. (userId=" + n.getUserId() + ")");
        }

        expoPushService.sendPush(
                user.getExpoPushToken(),
                n.getTitle(),
                n.getBody(),
                Map.of("notificationId", n.getId(), "type", n.getType())
        );

        // push_sent_at 갱신 (dirty checking으로 자동 UPDATE)
        n.setPushSentAt(LocalDateTime.now());

        log.info("[Admin] 수동 재발송 완료: notificationId={}, userId={}, token={}***",
                notificationId, n.getUserId(),
                user.getExpoPushToken().substring(0, Math.min(10, user.getExpoPushToken().length())));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 페이지네이션 응답 래퍼
    // ════════════════════════════════════════════════════════════════════════

    public record AdminPageResponse<T>(
            List<T> content,
            long    totalElements,
            int     totalPages,
            int     currentPage,
            int     pageSize
    ) {}

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
            Long    id,
            String  googlePlaceId,
            String  name,
            String  address,
            String  category,
            String  type,               // PlaceType enum
            String  space,              // Space enum
            String  mood,               // Mood enum
            Double  latitude,
            Double  longitude,
            Double  rating,
            Integer userRatingsTotal,
            Integer priceLevel,         // 0(무료) ~ 4(매우 비쌈)
            String  businessStatus,     // BusinessStatus enum
            String  phoneNumber,
            String  website,
            String  openingHours,       // JSONB raw string
            String  reviewData,         // JSONB raw string (AI 분석 리뷰)
            String  lastSyncedAt,
            String  analysisStatus
    ) {
        static AdminPlaceDto from(Place p) {
            boolean analyzed = p.getType() != null && p.getSpace() != null && p.getMood() != null;
            return new AdminPlaceDto(
                    p.getId(),
                    p.getGooglePlaceId(),
                    p.getName(),
                    p.getAddress(),
                    p.getCategory(),
                    p.getType()           != null ? p.getType().name()           : null,
                    p.getSpace()          != null ? p.getSpace().name()          : null,
                    p.getMood()           != null ? p.getMood().name()           : null,
                    p.getLatitude(),
                    p.getLongitude(),
                    p.getRating(),
                    p.getUserRatingsTotal(),
                    p.getPriceLevel(),
                    p.getBusinessStatus() != null ? p.getBusinessStatus().name() : null,
                    p.getPhoneNumber(),
                    p.getWebsite(),
                    p.getOpeningHours(),
                    p.getReviewData(),
                    p.getLastSyncedAt()   != null ? p.getLastSyncedAt().toString() : null,
                    analyzed ? "COMPLETE" : "PENDING");
        }
    }

    /** 사용자 무드 선호도 DTO */
    public record AdminMoodPreferenceDto(String mood, Double score) {}

    /** 대시보드 요약 통계 DTO */
    public record AdminStatsDto(
            long totalUsers,
            long totalTrips,
            long totalPlaces,
            long analyzedPlaces,
            long totalNotifications,
            long unsentNotifications,
            long totalBookmarks,
            long totalPreferences
    ) {}

    // ── 취향 DNA 분석 통계 ────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public AdminDnaStatsDto getDnaStats() {
        long totalPreferences = userPreferenceRepository.count();
        List<Object[]> rows   = userPreferenceRepository.findMoodStats();

        List<MoodStatDto> moodStats = rows.stream().map(row -> {
            String mood      = ((com.planb.planb_backend.domain.trip.entity.Mood) row[0]).name();
            double avgScore  = row[1] == null ? 0.0 : Math.round(((Number) row[1]).doubleValue() * 100.0) / 100.0;
            long   userCount = row[2] == null ? 0L  : ((Number) row[2]).longValue();
            long   positive  = row[3] == null ? 0L  : ((Number) row[3]).longValue();
            long   negative  = row[4] == null ? 0L  : ((Number) row[4]).longValue();
            return new MoodStatDto(mood, avgScore, userCount, positive, negative);
        }).toList();

        String topMood = moodStats.stream()
                .max(Comparator.comparingDouble(MoodStatDto::avgScore))
                .map(MoodStatDto::mood)
                .orElse(null);

        return new AdminDnaStatsDto(totalPreferences, topMood, moodStats);
    }

    /** 취향 DNA 분석 DTO */
    public record AdminDnaStatsDto(
            long totalPreferences,
            String topMood,
            List<MoodStatDto> moodStats
    ) {}

    /** Mood 단위 집계 */
    public record MoodStatDto(
            String mood,
            double avgScore,
            long   userCount,
            long   positive,
            long   negative
    ) {}

    /** 데이터 수치 탭 시계열 DTO */
    public record AdminTimeSeriesDto(
            List<String> labels,         // 14일 날짜 레이블
            List<Long>   dailyUsers,     // 14일 일별 신규 가입자
            List<Long>   dailyTrips,     // 14일 일별 신규 여행
            List<Long>   dailyPlaces,    // 14일 일별 AI 장소 분석 완료
            long   newUsersLast7,        // 현재 7일 신규 가입자 합계
            double growthUsers,          // 이전 7일 대비 성장률 (%)
            long   newTripsLast7,
            double growthTrips,
            long   newPlacesLast7,
            double growthPlaces,
            long   totalPreferences      // 누적 AI 추천 DNA 학습건
    ) {}

    /** 즐겨찾기 관리 DTO */
    public record AdminBookmarkDto(
            Long   bookmarkId,
            Long   userId,
            String userEmail,
            String userName,
            String googlePlaceId,
            String name,
            String category,
            String address,
            Double lat,
            Double lng,
            String createdAt
    ) {
        static AdminBookmarkDto from(Bookmark b, User user) {
            return new AdminBookmarkDto(
                    b.getId(),
                    b.getUserId(),
                    user != null ? user.getEmail()    : null,
                    user != null ? user.getNickname() : null,
                    b.getGooglePlaceId(),
                    b.getName(),
                    b.getCategory(),
                    b.getAddress(),
                    b.getLat(),
                    b.getLng(),
                    b.getCreatedAt() != null ? b.getCreatedAt().toString() : null
            );
        }
    }

    /**
     * 날씨 알림 모니터링 DTO
     * userEmail/userName: 발송 대상 사용자 식별
     * planName: TripPlace.name (알림이 발생한 일정 장소명)
     * tripTitle: Trip.title (해당 여행 제목)
     */
    public record AdminNotificationDto(
            Long    notificationId,
            Long    userId,
            String  userEmail,          // 사용자 이메일
            String  userName,           // 사용자 닉네임
            Long    planId,
            String  planName,           // TripPlace.name (일정 장소명)
            String  tripTitle,          // Trip.title (여행 제목)
            String  type,
            String  title,
            String  precipitationProb,  // "70%" 형식
            String  pushStatus,         // "발송됨" / "미발송"
            String  pushSentAt,
            String  createdAt
    ) {
        static AdminNotificationDto from(Notification n, User user, TripPlace tp, String tripTitle) {
            return new AdminNotificationDto(
                    n.getId(),
                    n.getUserId(),
                    user != null ? user.getEmail()    : null,
                    user != null ? user.getNickname() : null,
                    n.getPlanId(),
                    tp   != null ? tp.getName()       : null,
                    tripTitle,
                    n.getType(),
                    n.getTitle(),
                    n.getPrecipitationProb() != null ? n.getPrecipitationProb() + "%" : "—",
                    n.getPushSentAt() != null ? "발송됨" : "미발송",
                    n.getPushSentAt() != null ? n.getPushSentAt().toString() : null,
                    n.getCreatedAt()  != null ? n.getCreatedAt().toString()  : null
            );
        }
    }
}
