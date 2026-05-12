package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.GapInfo;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.trip.entity.TransportMode;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * [기능 6 — 틈새 추천] 한 trip 안에서 연속한 두 일정 사이의 "비는 시간"을 찾아낸다.
 * <p>
 * 규칙
 *  - 같은 trip 의 TripPlace 들을 날짜·방문순서 오름차순으로 훑는다.
 *  - 연속한 두 일정(A, B) 에서 A.endTime ~ B.visitTime 의 분이
 *    MIN_GAP_MINUTES(=30) 이상이면 갭으로 인정.
 *  - A.endTime 이 null 이면 A.visitTime + DEFAULT_PLAN_DURATION_MINUTES(60)으로 가정.
 *  - 같은 날짜 안의 갭만 본다 (다른 날 사이는 취침 시간이므로 제외).
 *  - availableMinutes = max(0, gap − directTravelTime(A→B, mode) − SAFETY_MIN(10))
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GapDetectionService {

    private static final int MIN_GAP_MINUTES = 60;
    private static final int DEFAULT_PLAN_DURATION_MINUTES = 60;
    private static final int SAFETY_MARGIN_MINUTES = 10;

    private final TripPlaceRepository tripPlaceRepository;
    private final TripRepository tripRepository;
    private final PlaceRepository placeRepository;

    /**
     * 이동 수단 미지정 시 trip.transportMode → 없으면 WALK.
     */
    @Transactional(readOnly = true)
    public List<GapInfo> detectGaps(Long tripId) {
        return detectGaps(tripId, null);
    }

    @Transactional(readOnly = true)
    public List<GapInfo> detectGaps(Long tripId, TransportMode mode) {
        TransportMode effective = (mode != null) ? mode
                : tripRepository.findById(tripId)
                        .map(t -> t.getTransportMode())
                        .orElse(null);
        if (effective == null) effective = TransportMode.WALK;

        List<TripPlace> places = tripPlaceRepository.findByTripIdOrderByDateAndVisitOrder(tripId);
        List<GapInfo> gaps = new ArrayList<>();

        for (int i = 0; i < places.size() - 1; i++) {
            TripPlace before = places.get(i);
            TripPlace after  = places.get(i + 1);

            LocalDate beforeDate = before.getItinerary().getDate();
            LocalDate afterDate  = after.getItinerary().getDate();

            // 다른 날짜 간 갭은 무시 (취침 시간)
            if (!beforeDate.equals(afterDate)) continue;

            LocalDateTime beforeEnd  = resolveEndTime(before, beforeDate);
            LocalDateTime afterStart = resolveStartTime(after, afterDate);
            if (beforeEnd == null || afterStart == null) continue;

            long gapMin = Duration.between(beforeEnd, afterStart).toMinutes();
            if (gapMin < MIN_GAP_MINUTES) continue;

            // 장소 좌표 조회 (Google Place ID 기반)
            Place bPlace = placeRepository.findByGooglePlaceId(before.getPlaceId()).orElse(null);
            Place aPlace = placeRepository.findByGooglePlaceId(after.getPlaceId()).orElse(null);

            int travelMin = computeDirectTravelMinutes(bPlace, aPlace, effective);
            int available = Math.max(0, (int) gapMin - travelMin - SAFETY_MARGIN_MINUTES);

            gaps.add(GapInfo.builder()
                    .beforePlanId(before.getTripPlaceId())
                    .beforePlanTitle(before.getName())
                    .beforePlanEndTime(beforeEnd)
                    .beforePlaceLat(bPlace != null ? bPlace.getLatitude() : null)
                    .beforePlaceLng(bPlace != null ? bPlace.getLongitude() : null)
                    .afterPlanId(after.getTripPlaceId())
                    .afterPlanTitle(after.getName())
                    .afterPlanStartTime(afterStart)
                    .afterPlaceLat(aPlace != null ? aPlace.getLatitude() : null)
                    .afterPlaceLng(aPlace != null ? aPlace.getLongitude() : null)
                    .gapMinutes((int) gapMin)
                    .availableMinutes(available)
                    .transportMode(effective)
                    .estimatedTravelMinutes(travelMin)
                    .build());
        }

        log.info("[GapDetectionService] tripId={}, mode={} 갭 {}건 검출",
                tripId, effective, gaps.size());
        return gaps;
    }

    /**
     * GapRecommendationService 에서 재사용할 수 있도록 public 노출.
     */
    public int computeDirectTravelMinutes(Place a, Place b, TransportMode mode) {
        if (a == null || b == null
                || a.getLatitude() == null || b.getLatitude() == null
                || mode == null) return 0;
        double km = haversineKm(a.getLatitude(), a.getLongitude(),
                                b.getLatitude(), b.getLongitude());
        return (int) Math.ceil(km / mode.getKmPerMin());
    }

    public static int getSafetyMarginMinutes() { return SAFETY_MARGIN_MINUTES; }

    // ─────────────────────────────────────────────────────────
    //  헬퍼
    // ─────────────────────────────────────────────────────────

    /** TripPlace 의 종료 시각 → LocalDateTime. endTime 없으면 visitTime + 60분. */
    private LocalDateTime resolveEndTime(TripPlace tp, LocalDate date) {
        if (tp.getEndTime() != null && !tp.getEndTime().isBlank()) {
            LocalTime t = parseTime(tp.getEndTime());
            return t != null ? LocalDateTime.of(date, t) : null;
        }
        if (tp.getVisitTime() != null && !tp.getVisitTime().isBlank()) {
            LocalTime t = parseTime(tp.getVisitTime());
            return t != null ? LocalDateTime.of(date, t).plusMinutes(DEFAULT_PLAN_DURATION_MINUTES) : null;
        }
        return null;
    }

    /** TripPlace 의 시작 시각 → LocalDateTime. */
    private LocalDateTime resolveStartTime(TripPlace tp, LocalDate date) {
        if (tp.getVisitTime() == null || tp.getVisitTime().isBlank()) return null;
        LocalTime t = parseTime(tp.getVisitTime());
        return t != null ? LocalDateTime.of(date, t) : null;
    }

    private LocalTime parseTime(String hhmm) {
        try {
            return LocalTime.parse(hhmm);
        } catch (Exception e) {
            return null;
        }
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
