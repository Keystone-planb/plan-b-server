package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.BusinessStatus;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.entity.TransportMode;
import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 대안 장소 추천 서비스 — 퍼널(Funnel) + 병렬 처리(Parallel) 아키텍처
 *
 * [전체 파이프라인]
 * ① Google Nearby Search로 후보 20개 수집
 * ② 1차 퍼널 필터링 (Hard Filter + 1차 스코어링) → 상위 7개 선발
 * ③ 7개에 대해 CompletableFuture 병렬 심층 분석 (Naver/Insta 스크래핑 + OpenAI)
 * ④ AI 2차 검열 → 최종 EllipticalBonus 스코어링 → 상위 5개 반환
 *
 * [성능]
 * 기존: 순차 처리 시 최대 400초
 * 변경: 병렬 처리로 가장 느린 단일 분석 시간(약 15초) 수준으로 단축
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final GooglePlaceApiService googlePlaceApiService;
    private final PlaceRepository placeRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final TripRepository tripRepository;
    private final ScoringStrategy scoringStrategy;
    private final PlaceAnalysisService placeAnalysisService;
    private final CongestionService congestionService;
    private final OpeningHoursService openingHoursService;

    // @RequiredArgsConstructor는 final 필드만 처리하므로 @Qualifier 주입은 별도 field injection 사용
    @Autowired
    @Qualifier("analysisExecutor")
    private Executor analysisExecutor;

    private static final int FUNNEL_TOP_N        = 7;  // 1차 퍼널 선발 인원
    private static final int FINAL_TOP_N         = 5;  // 최종 반환 인원
    private static final int MIN_REVIEW_COUNT    = 10; // Hard Filter: 최소 리뷰 수
    private static final long ANALYSIS_TIMEOUT_SEC = 45; // 병렬 분석 전체 타임아웃 (초)
    private static final int CACHE_VALID_DAYS    = 7;  // 분석 캐시 유효 기간 (일)

    // ═══════════════════════════════════════════════════════
    //  메인 추천 파이프라인
    // ═══════════════════════════════════════════════════════

    public List<Place> getRecommendations(UserContext context) {

        // [STEP 0] 이동 수단 자동 상속 — context 에 명시 없으면 trip.transportMode 사용
        resolveTransportMode(context);

        // [STEP 0.5] keepOriginalCategory: 원본 일정 장소의 PlaceType을 selectedType에 주입
        if (context.isKeepOriginalCategory()) {
            resolveOriginalCategory(context);
        }

        // [STEP 1] 다음 일정 자동 추적 (동선 가중치용)
        if (context.isConsiderNextPlan() && context.getNextLat() == null && context.getTripId() != null) {
            resolveNextDestination(context);
        }

        // [STEP 2] 검색 반경 계산 (이동 수단 반영)
        int radiusMeters = (int) Math.round(context.getSpeedKmPerMin() * 1000 * context.getRadiusMinute());
        log.info("[STEP 2] 검색 반경: {}m (mode={})", radiusMeters, context.getTransportMode());

        // [STEP 2.5] 이번 여행 중복 제외 목록 수집
        Set<String> excludedIds = collectExcludedPlaceIds(context);

        // [STEP 3] Google Nearby Search
        List<Map<String, Object>> googleResults = googlePlaceApiService.searchNearbyPlaces(
                context.getCurrentLat(),
                context.getCurrentLng(),
                radiusMeters,
                context.getRequestedCategory()
        );
        log.info("[STEP 3] Google 검색 결과: {}개", googleResults.size());

        // [STEP 4] DB Upsert (결과를 Place 엔티티로 변환·저장)
        // @Transactional 없이 실행 → saveAndFlush() 가 각각 즉시 커밋
        // → 이후 병렬 분석 스레드에서 READ COMMITTED로 정상 조회 가능
        List<Place> allCandidates = upsertCandidates(googleResults, excludedIds);
        log.info("[STEP 4] Upsert 완료: {}개 후보", allCandidates.size());

        // [STEP 5] 1차 퍼널 필터링 → 상위 7개 선발
        List<Place> top7 = applyFunnelFilter(allCandidates, context);
        log.info("[STEP 5] 1차 퍼널 선발: {}개", top7.size());

        // [STEP 5.5] 결과 부족 시 반경 스마트 확장
        if (top7.size() < 3 && context.getRadiusMinute() < 40) {
            int expanded = (int)(context.getRadiusMinute() * 1.5);
            log.info("[STEP 5.5] 반경 확장: {}분 → {}분", context.getRadiusMinute(), expanded);
            context.setRadiusMinute(expanded);
            return getRecommendations(context);
        }

        // [STEP 6] 2차 심층 분석 — CompletableFuture 병렬 처리
        List<Place> analyzed = parallelAnalyze(top7);

        // [STEP 6.5] AI 2차 검열 (keepOriginalCategory=false 일 때)
        List<Place> afterAiFilter = applyAiCategoryFilter(analyzed, context);

        // [STEP 6.6] 영업시간 필터 (틈새 추천 mustBeOpenAt 가 설정된 경우)
        List<Place> openNow = afterAiFilter.stream()
                .filter(p -> passesOpeningHoursFilter(p, context))
                .collect(Collectors.toList());

        // [STEP 7] 최종 EllipticalBonus 스코어링 → 상위 5개 선발
        List<Place> top5 = openNow.stream()
                .sorted((p1, p2) -> Double.compare(
                        scoringStrategy.calculateScore(p2, context),
                        scoringStrategy.calculateScore(p1, context)))
                .limit(FINAL_TOP_N)
                .collect(Collectors.toList());

        log.info("[STEP 7] 최종 추천 완료: {}개", top5.size());
        return top5;
    }

    // ═══════════════════════════════════════════════════════
    //  STEP 4: DB Upsert
    // ═══════════════════════════════════════════════════════

    /**
     * Google Nearby 결과를 DB에 저장(신규) 또는 갱신(기존)하여 Place 리스트로 반환
     * - @Transactional 없음: saveAndFlush()가 각 호출마다 독립 트랜잭션으로 즉시 커밋
     * - 병렬 분석 스레드가 READ COMMITTED로 커밋된 데이터를 읽을 수 있게 함
     */
    private List<Place> upsertCandidates(List<Map<String, Object>> googleResults,
                                          Set<String> excludedIds) {
        List<Place> candidates = new ArrayList<>();

        for (Map<String, Object> result : googleResults) {
            String gId = (String) result.get("place_id");
            if (gId == null) continue;

            // 이번 여행 중복 제외
            if (excludedIds.contains(gId)) {
                log.info("[Upsert-중복 제외] {}", gId);
                continue;
            }

            try {
                Place place = placeRepository.findByGooglePlaceId(gId)
                        .orElseGet(() -> {
                            Place newPlace = new Place();
                            newPlace.setGooglePlaceId(gId);
                            return newPlace;
                        });

                updatePlaceInfo(place, result);
                Place saved = placeRepository.saveAndFlush(place);
                candidates.add(saved);

            } catch (Exception e) {
                log.warn("[Upsert 실패] gId={}: {}", gId, e.getMessage());
            }
        }
        return candidates;
    }

    // ═══════════════════════════════════════════════════════
    //  STEP 5: 1차 퍼널 필터링
    // ═══════════════════════════════════════════════════════

    /**
     * Hard Filter + 1차 스코어링으로 상위 7개 선발
     *
     * Hard Filter 탈락 조건:
     *   1) businessStatus != OPERATIONAL
     *   2) 구글 리뷰 수 < 10
     *   3) 이름·좌표 누락
     *
     * 1차 스코어링:
     *   - 기초 신뢰도: 구글 평점 × 리뷰 수
     *   - 거리 페널티: 현재 위치 기준 Haversine 거리 (5km 기준 선형 감점)
     *   - 혼잡도 페널티: Redis TTL 2h 이내 제보 장소 → 점수 × 0.05 (사실상 후순위)
     */
    private List<Place> applyFunnelFilter(List<Place> candidates, UserContext context) {
        return candidates.stream()
                .filter(p -> {
                    // Hard Filter A: 영업 상태
                    if (p.getBusinessStatus() != null
                            && p.getBusinessStatus() != BusinessStatus.OPERATIONAL) {
                        log.info("[퍼널-탈락] 영업 중단 ({}): {}", p.getBusinessStatus(), p.getName());
                        return false;
                    }
                    return true;
                })
                .filter(p -> {
                    // Hard Filter B: 리뷰 수
                    int cnt = p.getUserRatingsTotal() != null ? p.getUserRatingsTotal() : 0;
                    if (cnt < MIN_REVIEW_COUNT) {
                        log.info("[퍼널-탈락] 리뷰 부족 ({}개): {}", cnt, p.getName());
                        return false;
                    }
                    return true;
                })
                .filter(p -> {
                    // Hard Filter C: 필수 메타데이터
                    boolean valid = p.getName() != null && !p.getName().isBlank()
                            && p.getLatitude() != null && p.getLongitude() != null;
                    if (!valid) log.info("[퍼널-탈락] 메타데이터 누락: id={}", p.getId());
                    return valid;
                })
                .sorted((p1, p2) -> Double.compare(
                        calculateFunnelScore(p2, context),
                        calculateFunnelScore(p1, context)))
                .limit(FUNNEL_TOP_N)
                .collect(Collectors.toList());
    }

    /**
     * 1차 퍼널 스코어 계산
     * - baseScore       = 구글 평점 × 리뷰 수  (기초 신뢰도)
     * - distancePenalty = max(0.1, 1.0 - distKm / 5.0)  (5km 기준 선형 감점)
     * - congestionMult  = Redis 혼잡 제보 있으면 0.05, 없으면 1.0
     */
    private double calculateFunnelScore(Place place, UserContext context) {
        double rating      = place.getRating() != null ? place.getRating() : 0.0;
        int    reviewCount = place.getUserRatingsTotal() != null ? place.getUserRatingsTotal() : 0;
        double baseScore   = rating * reviewCount;

        double distanceKm = scoringStrategy.haversine(
                context.getCurrentLat(), context.getCurrentLng(),
                place.getLatitude(), place.getLongitude());
        double distancePenalty = Math.max(0.1, 1.0 - (distanceKm / 5.0));

        double congestionMult = 1.0;
        if (place.getGooglePlaceId() != null
                && congestionService.isCongested(place.getGooglePlaceId())) {
            log.info("[퍼널-혼잡 페널티] {}", place.getName());
            congestionMult = 0.05;
        }

        return baseScore * distancePenalty * congestionMult;
    }

    // ═══════════════════════════════════════════════════════
    //  STEP 6: 병렬 심층 분석
    // ═══════════════════════════════════════════════════════

    /**
     * 상위 7개 장소에 대해 Naver/Insta 스크래핑 + OpenAI 분석을 CompletableFuture로 병렬 실행
     *
     * - 각 Future는 analysisExecutor(corePoolSize=7)에서 동시 실행
     * - allOf().get(45s) 로 가장 느린 작업 완료 시점에 취합
     * - 개별 분석 실패(외부 API 오류, Rate Limit 등)는 exceptionally에서 캐치 →
     *   원본 place 반환으로 전체 흐름 차단 방지
     * - 타임아웃 초과 시 취합된 결과만 반환 (완료된 분석은 유효)
     */
    private List<Place> parallelAnalyze(List<Place> top7) {
        log.info("[STEP 6] 병렬 심층 분석 시작: {}개 장소", top7.size());

        List<CompletableFuture<Place>> futures = top7.stream()
                .map(place -> CompletableFuture
                        .supplyAsync(() -> analyzeOnce(place), analysisExecutor)
                        .exceptionally(ex -> {
                            log.warn("[병렬 분석 실패] {} — 원본 유지: {}",
                                    place.getName(), ex.getMessage());
                            return place;
                        }))
                .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(ANALYSIS_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[병렬 분석 타임아웃] {}초 초과 — 완료된 결과만 사용", ANALYSIS_TIMEOUT_SEC);
        } catch (Exception e) {
            log.warn("[병렬 분석 취합 오류]: {}", e.getMessage());
        }

        List<Place> results = futures.stream()
                .map(f -> f.getNow(null))   // 완료됐으면 결과, 아직이면 null
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.info("[STEP 6] 병렬 분석 완료: {}개 취합", results.size());
        return results;
    }

    /**
     * 단일 장소 심층 분석 (병렬 작업 단위)
     *
     * [캐시 체크] lastSyncedAt이 CACHE_VALID_DAYS(7일) 이내이면 재분석 스킵 → 즉시 반환
     *   - 인기 지역 재요청 시 OpenAI 호출 없이 DB 캐시 데이터 사용
     *   - 효과: 기분석 장소 비율이 높을수록 전체 응답시간 대폭 단축
     *
     * [분석 실행] 캐시 미스 or 오래된 데이터 → processPlaceAnalysis 위임
     *   - 실패 시 enrichBusinessInfo로 경량 보강 후 반환 (전체 흐름 차단 방지)
     */
    private Place analyzeOnce(Place place) {
        // 캐시 히트: 7일 이내 분석된 장소는 재분석 스킵
        if (place.getLastSyncedAt() != null
                && place.getLastSyncedAt().isAfter(LocalDateTime.now().minusDays(CACHE_VALID_DAYS))) {
            log.info("[분석 캐시 히트] {} — 마지막 분석: {}", place.getName(), place.getLastSyncedAt());
            return place;
        }

        // 캐시 미스: 신규 or 오래된 장소 → AI 분석 실행
        log.info("[분석 실행] {} (lastSyncedAt={})", place.getName(), place.getLastSyncedAt());
        try {
            return placeAnalysisService.processPlaceAnalysis(place.getId());
        } catch (Exception e) {
            log.warn("[단일 분석 오류] {} (id={}): {} — 영업정보 경량 보강 시도",
                    place.getName(), place.getId(), e.getMessage());
            enrichBusinessInfo(place);
            return place;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  STEP 6.5: AI 2차 검열
    // ═══════════════════════════════════════════════════════

    /**
     * keepOriginalCategory=false 일 때,
     * AI가 분석한 PlaceType이 요청 카테고리와 다른 장소를 제외
     */
    private List<Place> applyAiCategoryFilter(List<Place> places, UserContext context) {
        if (context.isKeepOriginalCategory()) return places;

        String requested = context.getRequestedCategory();
        if (requested == null || requested.isBlank()) return places;

        return places.stream()
                .filter(p -> {
                    PlaceType aiType = p.getType();
                    if (aiType == null) return true; // 분석 미완료 장소는 통과
                    if (!aiType.name().equalsIgnoreCase(requested)) {
                        log.info("[AI 2차 검열-탈락] {}: 요청={}, AI분석={}",
                                p.getName(), requested, aiType);
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════
    //  헬퍼 메서드
    // ═══════════════════════════════════════════════════════

    /** 같은 여행에 이미 등록된 Google Place ID 수집 (중복 제외용) */
    private Set<String> collectExcludedPlaceIds(UserContext context) {
        Set<String> excluded = new HashSet<>();
        if (context.getTripId() == null) return excluded;

        tripPlaceRepository.findByTripId(context.getTripId())
                .forEach(tp -> {
                    if (tp.getPlaceId() != null) excluded.add(tp.getPlaceId());
                });
        log.info("[중복 제외] 같은 여행 등록 장소 {}개", excluded.size());
        return excluded;
    }

    /**
     * 분석 실패한 장소에 대한 경량 영업정보 보강
     * Nearby Search에 없는 phone, website, full opening_hours 를 Place Details API로 채움
     */
    private void enrichBusinessInfo(Place place) {
        if (place.getGooglePlaceId() == null) return;
        try {
            Map<String, Object> details = googlePlaceApiService.getPlaceBusinessInfo(place.getGooglePlaceId());
            if (details == null || details.isEmpty()) return;

            if (place.getAddress() == null && details.containsKey("formatted_address")) {
                String addr = (String) details.get("formatted_address");
                if (addr != null && !addr.isBlank()) place.setAddress(addr);
            }
            if (place.getPhoneNumber() == null && details.containsKey("formatted_phone_number")) {
                place.setPhoneNumber((String) details.get("formatted_phone_number"));
            }
            if (place.getWebsite() == null && details.containsKey("website")) {
                place.setWebsite((String) details.get("website"));
            }
            if (place.getPriceLevel() == null && details.containsKey("price_level")) {
                place.setPriceLevel(((Number) details.get("price_level")).intValue());
            }
            if (place.getBusinessStatus() == null && details.containsKey("business_status")) {
                String bsRaw = (String) details.get("business_status");
                try {
                    place.setBusinessStatus(BusinessStatus.valueOf(bsRaw.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    log.warn("[영업정보 보강] 알 수 없는 business_status: {}", bsRaw);
                }
            }
            if (details.containsKey("opening_hours")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    place.setOpeningHours(mapper.writeValueAsString(details.get("opening_hours")));
                } catch (Exception e) {
                    log.warn("[영업정보 보강] opening_hours 직렬화 실패: {}", place.getGooglePlaceId());
                }
            }
            placeRepository.saveAndFlush(place);
            log.info("[영업정보 경량 보강 완료] {}", place.getName());
        } catch (Exception e) {
            log.warn("[영업정보 보강 실패] {}: {}", place.getName(), e.getMessage());
        }
    }

    /**
     * 다음 목적지 자동 추적
     * 현재 여행(tripId) 기준 오늘 이후 TripPlace 중 현재 시각 이후 첫 번째 일정 → UserContext 주입
     */
    private void resolveNextDestination(UserContext context) {
        LocalDate today   = LocalDate.now();
        String   nowTime  = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        List<TripPlace> upcoming = tripPlaceRepository.findUpcomingByTripId(context.getTripId(), today);

        for (TripPlace tp : upcoming) {
            if (tp.getItinerary().getDate().isEqual(today)) {
                String visitTime = tp.getVisitTime();
                if (visitTime == null || visitTime.compareTo(nowTime) <= 0) continue;
            }
            Place nextPlace = placeRepository.findByGooglePlaceId(tp.getPlaceId()).orElse(null);
            if (nextPlace != null && nextPlace.getLatitude() != null && nextPlace.getLongitude() != null) {
                context.setNextLat(nextPlace.getLatitude());
                context.setNextLng(nextPlace.getLongitude());
                log.info("[다음 목적지 자동 탐색] 장소: {}, 날짜: {}, 시간: {}",
                        tp.getName(), tp.getItinerary().getDate(), tp.getVisitTime());
                return;
            }
        }
        log.info("[다음 목적지 탐색] 조건 맞는 일정 없음 — 동선 보너스 비활성화");
    }

    // ═══════════════════════════════════════════════════════
    //  SSE 스트리밍 추천 (신규)
    // ═══════════════════════════════════════════════════════

    /**
     * 대안 장소 추천 — SSE 스트리밍 버전
     *
     * [이벤트 흐름]
     * 1) progress 이벤트: 연결 즉시 전송 → 프론트 스켈레톤 UI 렌더링 트리거
     * 2) place 이벤트: 병렬 분석 완료 순서대로 1개씩 push (최대 5개)
     * 3) done 이벤트: 전체 완료 신호 → 프론트 연결 종료
     *
     * [동시성 처리]
     * - AtomicBoolean(done): emitter 이미 종료된 경우 추가 send 차단
     * - AtomicInteger(emittedCount): 5개 초과 전송 방지 (CAS 기반 원자 증가)
     * - send() 실패(IOException/IllegalStateException) 시 completeWithError 호출
     */
    public SseEmitter streamRecommendations(UserContext context) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        doStreamAsync(context, emitter);
        return emitter;
    }

    /**
     * SSE 파이프라인 실행 — 테스트 가능하도록 분리된 package-private 메서드
     * emitter에 진행 상황을 progress → place(×N) → done 순으로 전송한다.
     */
    void doStreamAsync(UserContext context, SseEmitter emitter) {
        AtomicBoolean done = new AtomicBoolean(false);

        emitter.onTimeout(() -> {
            log.warn("[SSE] 연결 타임아웃");
            done.set(true);
        });
        emitter.onCompletion(() -> done.set(true));
        emitter.onError(ex -> {
            log.warn("[SSE] 연결 오류: {}", ex.getMessage());
            done.set(true);
        });

        // 전체 파이프라인을 비동기 실행 → 컨트롤러가 즉시 SseEmitter 반환 가능
        CompletableFuture.runAsync(() -> {
            try {
                // [S1] 스켈레톤 UI 트리거용 progress 이벤트 즉시 전송
                sendSse(emitter, done, "progress", Map.of(
                        "message", "AI가 주변 장소를 분석 중입니다...",
                        "total", FINAL_TOP_N
                ));

                // [S1.5] 이동 수단 자동 상속
                resolveTransportMode(context);

                // [S1.6] keepOriginalCategory: 원본 일정 장소의 PlaceType을 selectedType에 주입
                if (context.isKeepOriginalCategory()) {
                    resolveOriginalCategory(context);
                }

                // [S2] 다음 목적지 자동 추적
                if (context.isConsiderNextPlan() && context.getNextLat() == null
                        && context.getTripId() != null) {
                    resolveNextDestination(context);
                }

                // [S3] 검색 반경 계산 (이동 수단 반영)
                int radiusMeters = (int) Math.round(context.getSpeedKmPerMin() * 1000 * context.getRadiusMinute());

                // [S4] 중복 제외 목록
                Set<String> excludedIds = collectExcludedPlaceIds(context);

                // [S5] Google Nearby Search
                List<Map<String, Object>> googleResults = googlePlaceApiService.searchNearbyPlaces(
                        context.getCurrentLat(), context.getCurrentLng(),
                        radiusMeters, context.getRequestedCategory()
                );

                // [S6] DB Upsert
                List<Place> allCandidates = upsertCandidates(googleResults, excludedIds);

                // [S7] 1차 퍼널 필터링 → 상위 7개
                List<Place> top7 = applyFunnelFilter(allCandidates, context);
                log.info("[SSE] 퍼널 선발 {}개 → 병렬 분석 시작", top7.size());

                // [S8] 병렬 분석 — 완료된 장소부터 즉시 emit
                AtomicInteger emittedCount = new AtomicInteger(0);

                List<CompletableFuture<Void>> futures = top7.stream()
                        .map(place -> CompletableFuture
                                .supplyAsync(() -> analyzeOnce(place), analysisExecutor)
                                .thenAccept(analyzed -> {
                                    if (done.get()) return;
                                    if (emittedCount.get() >= FINAL_TOP_N) return;

                                    // AI 2차 검열 (단일 장소)
                                    if (!passesAiCategoryFilter(analyzed, context)) {
                                        log.info("[SSE-탈락] AI 카테고리 불일치: {}", analyzed.getName());
                                        return;
                                    }

                                    // [기능 6 — 틈새 추천] 영업시간 필터
                                    if (!passesOpeningHoursFilter(analyzed, context)) {
                                        log.info("[SSE-탈락] 영업시간 불일치: {}", analyzed.getName());
                                        return;
                                    }

                                    // 원자적 증가 — 정확히 FINAL_TOP_N개만 전송
                                    int idx = emittedCount.incrementAndGet();
                                    if (idx <= FINAL_TOP_N) {
                                        log.info("[SSE] place 이벤트 전송 ({}/{}): {}",
                                                idx, FINAL_TOP_N, analyzed.getName());
                                        sendSse(emitter, done, "place",
                                                com.planb.planb_backend.domain.recommendation.dto.PlaceResult.from(analyzed));
                                    }
                                })
                                .exceptionally(ex -> {
                                    log.warn("[SSE] 단일 장소 분석 실패: {}", ex.getMessage());
                                    return null;
                                }))
                        .collect(Collectors.toList());

                // 전체 완료 대기 (타임아웃 초과 시 완료된 결과만 사용)
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(ANALYSIS_TIMEOUT_SEC, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.warn("[SSE] 전체 분석 타임아웃 — 완료된 결과만 전송");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // [S9] done 이벤트 전송 및 연결 종료
                sendSse(emitter, done, "done", "[DONE]");
                if (!done.get()) emitter.complete();

            } catch (Exception e) {
                log.error("[SSE] 스트리밍 처리 오류: ", e);
                if (!done.get()) emitter.completeWithError(e);
            }
        }, analysisExecutor);
    }

    /**
     * 단일 장소 AI 카테고리 검열
     * applyAiCategoryFilter() 의 단일 장소 버전 — SSE 스트리밍에서 사용
     */
    private boolean passesAiCategoryFilter(Place place, UserContext context) {
        if (context.isKeepOriginalCategory()) return true;
        String requested = context.getRequestedCategory();
        if (requested == null || requested.isBlank()) return true;
        PlaceType aiType = place.getType();
        if (aiType == null) return true; // 미분석 장소는 통과
        return aiType.name().equalsIgnoreCase(requested);
    }

    /**
     * [기능 6 — 틈새 추천] 단일 장소 영업시간 필터
     * mustBeOpenAt 이 null 이면 항상 통과 (기존 SOS/날씨 알림에서는 null 로 두면 됨).
     */
    private boolean passesOpeningHoursFilter(Place place, UserContext context) {
        if (context.getMustBeOpenAt() == null) return true;
        return openingHoursService.isOpenAt(place.getOpeningHours(), context.getMustBeOpenAt());
    }

    /**
     * keepOriginalCategory=true 일 때:
     * currentPlanId(TripPlace)의 원본 장소 PlaceType을 조회하여 context.selectedType에 주입.
     * → Google Nearby Search 때 원본 카테고리로 필터링, AI 2차 검열은 별도로 스킵됨.
     *
     * AI 분석 미완료(place.type = null)인 경우 카테고리 필터 없이 진행.
     */
    private void resolveOriginalCategory(UserContext context) {
        if (context.getCurrentPlanId() == null) return;
        try {
            tripPlaceRepository.findById(context.getCurrentPlanId()).ifPresent(tp -> {
                if (tp.getPlaceId() == null) return;
                placeRepository.findByGooglePlaceId(tp.getPlaceId()).ifPresent(place -> {
                    if (place.getType() != null) {
                        context.setSelectedType(place.getType().name());
                        log.info("[OriginalCategory] planId={} → 원본 카테고리={} 적용",
                                context.getCurrentPlanId(), place.getType().name());
                    } else {
                        log.warn("[OriginalCategory] planId={} 원본 장소 AI 분석 미완료 — 카테고리 필터 없이 진행",
                                context.getCurrentPlanId());
                    }
                });
            });
        } catch (Exception e) {
            log.warn("[OriginalCategory] 카테고리 조회 실패 (planId={}): {}",
                    context.getCurrentPlanId(), e.getMessage());
        }
    }

    /**
     * 이동 수단 자동 상속.
     * context 에 transportMode 가 없으면 trip.transportMode 로 채운다. 그것도 없으면 WALK.
     */
    private void resolveTransportMode(UserContext context) {
        if (context.getTransportMode() != null) return;
        if (context.getTripId() == null) return;
        tripRepository.findById(context.getTripId())
                .map(Trip::getTransportMode)
                .ifPresent(mode -> {
                    context.setTransportMode(mode);
                    log.info("[ModeResolve] tripId={} 의 transportMode={} 자동 적용",
                            context.getTripId(), mode);
                });
    }

    /**
     * SSE 이벤트 전송 헬퍼
     * - String 데이터 → text/plain (done 이벤트의 "[DONE]" 등)
     * - 그 외 객체 → application/json (progress, place 이벤트)
     * - IOException / IllegalStateException 발생 시 completeWithError 호출
     */
    private void sendSse(SseEmitter emitter, AtomicBoolean done, String eventName, Object data) {
        if (done.get()) return;
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(eventName);
            if (data instanceof String) {
                builder.data(data, MediaType.TEXT_PLAIN);
            } else {
                builder.data(data, MediaType.APPLICATION_JSON);
            }
            emitter.send(builder);
        } catch (IOException | IllegalStateException e) {
            log.warn("[SSE] 이벤트 전송 실패 (event={}): {}", eventName, e.getMessage());
            done.set(true);
            emitter.completeWithError(e);
        }
    }

    /**
     * Google Nearby Search 결과로 Place 엔티티 정보 업데이트
     * (Nearby Search 제공 필드: name, geometry, rating, user_ratings_total,
     *  business_status, opening_hours(open_now), price_level, photos, vicinity)
     */
    private void updatePlaceInfo(Place place, Map<String, Object> result) {
        place.setName((String) result.get("name"));

        String vicinity = (String) result.get("vicinity");
        if (vicinity != null && !vicinity.isBlank()) {
            place.setAddress(vicinity);
        }

        try {
            Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
            Map<String, Object> location = (Map<String, Object>) geometry.get("location");
            place.setLatitude(((Number) location.get("lat")).doubleValue());
            place.setLongitude(((Number) location.get("lng")).doubleValue());
        } catch (Exception e) {
            log.warn("[updatePlaceInfo] 위경도 추출 실패: {}", place.getGooglePlaceId());
        }

        if (result.containsKey("rating")) {
            place.setRating(((Number) result.get("rating")).doubleValue());
        }
        if (result.containsKey("user_ratings_total")) {
            place.setUserRatingsTotal(((Number) result.get("user_ratings_total")).intValue());
        }

        if (result.containsKey("business_status")) {
            try {
                place.setBusinessStatus(BusinessStatus.valueOf(
                        ((String) result.get("business_status")).toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("[updatePlaceInfo] 알 수 없는 business_status: {}", result.get("business_status"));
            }
        }
        if (result.containsKey("price_level")) {
            place.setPriceLevel(((Number) result.get("price_level")).intValue());
        }
        if (result.containsKey("opening_hours")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                place.setOpeningHours(mapper.writeValueAsString(result.get("opening_hours")));
            } catch (Exception e) {
                log.warn("[updatePlaceInfo] opening_hours 직렬화 실패: {}", place.getGooglePlaceId());
            }
        }
        if (result.containsKey("photos")) {
            try {
                List<Map<String, Object>> photos = (List<Map<String, Object>>) result.get("photos");
                if (photos != null && !photos.isEmpty()) {
                    String photoRef = (String) photos.get(0).get("photo_reference");
                    if (photoRef != null) {
                        place.setPhotoUrl(
                                "https://maps.googleapis.com/maps/api/place/photo?maxwidth=400&photoreference="
                                        + photoRef);
                    }
                }
            } catch (Exception e) {
                log.warn("[updatePlaceInfo] photo_url 추출 실패: {}", place.getGooglePlaceId());
            }
        }
    }
}
