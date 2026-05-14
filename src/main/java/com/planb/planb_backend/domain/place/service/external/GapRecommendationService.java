package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.GapRecommendationRequest;
import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.trip.entity.TransportMode;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * [기능 6 — 틈새 추천] 특정 갭에 대해 SSE 스트리밍으로 장소를 추천한다.
 * <p>
 * 자동 채워지는 컨텍스트
 *  - currentLat/Lng     = before 일정 장소 좌표
 *  - transportMode      = 요청값 → trip.transportMode → WALK 순 폴백
 *  - radiusMinute       = 요청값 → "가용시간의 1/3" 자동 산출
 *  - considerNextPlan   = true, nextLat/Lng = after 일정 장소 좌표 (길목 보너스)
 *  - mustBeOpenAt       = beforeEnd + 직선이동 절반 시간 (실제 도착 시각 추정)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GapRecommendationService {

    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceRepository placeRepository;
    private final RecommendationService recommendationService;
    private final GapDetectionService gapDetectionService;
    private final GooglePlaceApiService googlePlaceApiService;

    /**
     * SSE 스트리밍 방식 틈새 추천.
     * 완료 순서대로 place 이벤트를 push 한 뒤 done 이벤트를 전송한다.
     * <p>
     * @Transactional: 동기 데이터 로딩 시 LAZY 관계 접근을 위해 필요.
     * 비동기 부분(doStreamAsync)은 별도 스레드에서 각자 트랜잭션을 열므로 영향 없음.
     */
    @Transactional(readOnly = true)
    public SseEmitter streamRecommendForGap(GapRecommendationRequest req) {
        if (req.getBeforePlanId() == null || req.getAfterPlanId() == null) {
            throw new IllegalArgumentException("beforePlanId / afterPlanId 가 필요합니다.");
        }

        TripPlace before = tripPlaceRepository.findById(req.getBeforePlanId())
                .orElseThrow(() -> new IllegalArgumentException("이전 일정을 찾을 수 없습니다."));
        TripPlace after  = tripPlaceRepository.findById(req.getAfterPlanId())
                .orElseThrow(() -> new IllegalArgumentException("다음 일정을 찾을 수 없습니다."));

        Long tripId = before.getItinerary().getTrip().getTripId();
        if (!tripId.equals(after.getItinerary().getTrip().getTripId())) {
            throw new IllegalArgumentException("두 일정이 같은 여행에 속하지 않습니다.");
        }
        if (req.getTripId() != null && !req.getTripId().equals(tripId)) {
            throw new IllegalArgumentException("tripId 가 일정의 trip 과 일치하지 않습니다.");
        }

        // 장소 좌표 조회 (DB 미존재 시 Google API 폴백)
        Place bPlace = resolvePlace(before.getPlaceId());
        Place aPlace = resolvePlace(after.getPlaceId());

        if (bPlace == null || bPlace.getLatitude() == null || bPlace.getLongitude() == null) {
            throw new IllegalStateException("이전 일정에 좌표가 없어 틈새 추천을 생성할 수 없습니다.");
        }

        // 시간 계산
        LocalDate date = before.getItinerary().getDate();
        LocalDateTime beforeEnd  = resolveEndTime(before, date);
        LocalDateTime afterStart = resolveStartTime(after, after.getItinerary().getDate());

        if (beforeEnd == null || afterStart == null) {
            throw new IllegalStateException("일정에 시간 정보가 없어 틈새 계산이 불가합니다.");
        }

        long gapMin = Duration.between(beforeEnd, afterStart).toMinutes();

        // 이동 수단 결정
        TransportMode mode = resolveMode(req, tripId);

        int travelMin = gapDetectionService.computeDirectTravelMinutes(bPlace, aPlace, mode);
        int availableMin = Math.max(0,
                (int) gapMin - travelMin - GapDetectionService.getSafetyMarginMinutes());

        // 검색 반경 (분): 요청값 우선, 없으면 가용시간의 1/3 (최소 5분)
        int radiusMinute = (req.getRadiusMinute() != null)
                ? req.getRadiusMinute()
                : Math.max(5, availableMin / 3);

        // 영업시간 검사 시각: 이동 절반 시간 이후 도착 시점 추정
        LocalDateTime checkAt = beforeEnd.plusMinutes(Math.max(5, travelMin / 2));

        UserContext ctx = UserContext.builder()
                .userId(req.getUserId())
                .tripId(tripId)
                .currentLat(bPlace.getLatitude())
                .currentLng(bPlace.getLongitude())
                .radiusMinute(radiusMinute)
                .transportMode(mode)
                .selectedType(null)
                .selectedSpace(null)
                .keepOriginalCategory(false)
                .considerNextPlan(true)
                .nextLat(aPlace != null ? aPlace.getLatitude() : null)
                .nextLng(aPlace != null ? aPlace.getLongitude() : null)
                .mustBeOpenAt(checkAt)
                .build();

        log.info("[GapRecommendation] tripId={}, gap={}분, A→B 이동={}분({}), 가용={}분, 반경={}분, 영업검사={}",
                tripId, gapMin, travelMin, mode, availableMin, radiusMinute, checkAt);

        SseEmitter emitter = new SseEmitter(90_000L);
        recommendationService.doStreamAsync(ctx, emitter);
        return emitter;
    }

    // ─────────────────────────────────────────────────────────
    //  헬퍼
    // ─────────────────────────────────────────────────────────

    /**
     * 이동 수단 결정.
     * req 에 명시된 값이 있으면 그것을 사용.
     * 없으면 null 반환 → RecommendationService.resolveTransportMode() 가 trip.transportMode 를 자동 적용.
     */
    private TransportMode resolveMode(GapRecommendationRequest req, Long tripId) {
        return req.getTransportMode(); // null 이면 RecommendationService 가 trip 에서 자동 상속
    }

    /**
     * 장소 좌표 조회: DB 우선, 없으면 Google Place Details API 폴백.
     * Google API도 실패하면 null 반환.
     */
    private Place resolvePlace(String googlePlaceId) {
        if (googlePlaceId == null || googlePlaceId.isBlank()) return null;

        Place found = placeRepository.findByGooglePlaceId(googlePlaceId).orElse(null);
        if (found != null && found.getLatitude() != null) return found;

        // Google API 폴백
        Map<String, Object> details = googlePlaceApiService.getGooglePlaceDetails(googlePlaceId);
        if (details.isEmpty()) return null;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> geometry = (Map<String, Object>) details.get("geometry");
            @SuppressWarnings("unchecked")
            Map<String, Object> location = (Map<String, Object>) geometry.get("location");
            double lat = ((Number) location.get("lat")).doubleValue();
            double lng = ((Number) location.get("lng")).doubleValue();

            Place stub = new Place();
            stub.setLatitude(lat);
            stub.setLongitude(lng);
            log.info("[GapRecommendation] Google API 폴백 좌표 조회 성공: {} → ({}, {})", googlePlaceId, lat, lng);
            return stub;
        } catch (Exception e) {
            log.warn("[GapRecommendation] Google API 폴백 좌표 파싱 실패: {}", googlePlaceId);
            return null;
        }
    }

    private LocalDateTime resolveEndTime(TripPlace tp, LocalDate date) {
        if (tp.getEndTime() != null && !tp.getEndTime().isBlank()) {
            LocalTime t = parseTime(tp.getEndTime());
            return t != null ? LocalDateTime.of(date, t) : null;
        }
        if (tp.getVisitTime() != null && !tp.getVisitTime().isBlank()) {
            LocalTime t = parseTime(tp.getVisitTime());
            return t != null ? LocalDateTime.of(date, t).plusMinutes(60) : null;
        }
        return null;
    }

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
}
