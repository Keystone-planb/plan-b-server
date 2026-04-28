package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final GooglePlaceApiService googlePlaceApiService;
    private final PlaceRepository placeRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final ScoringStrategy scoringStrategy;

    @Transactional
    public List<Place> getRecommendations(UserContext context) {

        // [STEP 1] 다음 일정 자동 추적 (동선 가중치용)
        // considerNextPlan=true인데 프론트에서 좌표를 안 넘긴 경우, DB에서 자동 조회
        if (context.isConsiderNextPlan() && context.getNextLat() == null && context.getUserId() != null) {
            resolveNextDestination(context);
        }

        // [STEP 2] 검색 반경 계산
        int radiusMeters = context.isWalk() ? (context.getRadiusMinute() * 80) : (context.getRadiusMinute() * 400);
        log.info("검색 반경 설정: {}m (도보여부: {})", radiusMeters, context.isWalk());

        // [STEP 2.5] 이번 여행에 이미 등록된 장소 목록 수집 (중복 제외용)
        Set<String> excludedGooglePlaceIds = new HashSet<>();
        if (context.getTripId() != null) {
            tripPlaceRepository.findByTripId(context.getTripId())
                    .forEach(tp -> {
                        if (tp.getPlaceId() != null) {
                            excludedGooglePlaceIds.add(tp.getPlaceId());
                        }
                    });
            log.info("[중복 제외] 같은 여행 등록 장소 {}개 제외 처리", excludedGooglePlaceIds.size());
        }

        // [STEP 3] 구글 Nearby Search로 데이터 수집
        List<Map<String, Object>> googleResults = googlePlaceApiService.searchNearbyPlaces(
                context.getCurrentLat(),
                context.getCurrentLng(),
                radiusMeters,
                context.getRequestedCategory()
        );

        List<Place> candidates = new ArrayList<>();

        // [STEP 4] 데이터 계층화 및 '중복 체크/Upsert' 루프
        for (Map<String, Object> result : googleResults) {
            String gId = (String) result.get("place_id");

            // ① 영업 상태 필터 — OPERATIONAL인 것만 통과
            String businessStatus = (String) result.get("business_status");
            if (businessStatus != null && !"OPERATIONAL".equalsIgnoreCase(businessStatus)) {
                log.info(">>>> [영업 상태 탈락] gId={}, status={}", gId, businessStatus);
                continue;
            }

            // ② 이번 여행 중복 제외
            if (excludedGooglePlaceIds.contains(gId)) {
                log.info(">>>> [중복 일정 탈락] 이미 여행에 등록된 장소: {}", gId);
                continue;
            }

            // DB Upsert
            Place place;
            Optional<Place> existingPlaceOpt = placeRepository.findByGooglePlaceId(gId);
            if (existingPlaceOpt.isPresent()) {
                place = existingPlaceOpt.get();
                log.info(">>>> [기존 장소 사용] {}", place.getName());
                updatePlaceInfo(place, result);
                placeRepository.saveAndFlush(place);
            } else {
                Place newPlace = new Place();
                newPlace.setGooglePlaceId(gId);
                updatePlaceInfo(newPlace, result);
                place = placeRepository.saveAndFlush(newPlace);
                log.info(">>>> [신규 장소 등록] AI 분석 보류: {}", place.getName());
            }

            // ③ AI 2차 검열 — keepOriginalCategory=true 이면 스킵
            if (!context.isKeepOriginalCategory()) {
                String requestedCategory = context.getRequestedCategory();
                PlaceType aiDetectedType = place.getType();
                if (requestedCategory != null && aiDetectedType != null) {
                    if (!aiDetectedType.name().equalsIgnoreCase(requestedCategory)) {
                        log.info(">>>> [2차 검열 탈락] 장소: {}, 요청: {}, AI분석: {} → 제외",
                                place.getName(), requestedCategory, aiDetectedType);
                        continue;
                    }
                }
            }

            candidates.add(place);
        }

        // [STEP 5] 지능형 범위 확장 (Smart Expansion)
        if (candidates.size() < 3 && context.getRadiusMinute() < 40) {
            int expandedMinute = (int)(context.getRadiusMinute() * 1.5);
            log.info("검색 결과 부족으로 범위 확장: {}분 -> {}분", context.getRadiusMinute(), expandedMinute);
            context.setRadiusMinute(expandedMinute);
            return getRecommendations(context);
        }

        // [STEP 6] 최종 스코어링 및 상위 5개 선발
        return candidates.stream()
                .sorted((p1, p2) -> Double.compare(
                        scoringStrategy.calculateScore(p2, context),
                        scoringStrategy.calculateScore(p1, context)
                ))
                .limit(5)
                .collect(Collectors.toList());
    }

    /**
     * 다음 목적지 자동 추적
     * - 오늘 이후 TripPlace 중 현재 시각 이후 첫 번째 일정을 찾아 UserContext에 주입
     * - Place 좌표가 DB에 없으면 건너뜀
     */
    private void resolveNextDestination(UserContext context) {
        LocalDate today = LocalDate.now();
        String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        List<TripPlace> upcoming = tripPlaceRepository.findUpcomingByUserId(context.getUserId(), today);

        for (TripPlace tp : upcoming) {
            // 오늘 일정이면 현재 시각 이후인 것만 허용
            if (tp.getItinerary().getDate().isEqual(today)) {
                String visitTime = tp.getVisitTime();
                if (visitTime == null || visitTime.compareTo(nowTime) <= 0) continue;
            }

            // Google Place ID로 DB에서 좌표 조회
            Place nextPlace = placeRepository.findByGooglePlaceId(tp.getPlaceId()).orElse(null);
            if (nextPlace != null && nextPlace.getLatitude() != null && nextPlace.getLongitude() != null) {
                context.setNextLat(nextPlace.getLatitude());
                context.setNextLng(nextPlace.getLongitude());
                log.info("[다음 목적지 자동 탐색] 장소: {}, 날짜: {}, 시간: {}",
                        tp.getName(), tp.getItinerary().getDate(), tp.getVisitTime());
                return;
            }
        }
        log.info("[다음 목적지 탐색] 조건에 맞는 다음 일정 없음 — 동선 보너스 비활성화");
    }

    /**
     * 구글 검색 결과로부터 Place 엔티티의 기본 정보를 업데이트하는 공통 메서드
     */
    private void updatePlaceInfo(Place place, Map<String, Object> result) {
        place.setName((String) result.get("name"));

        try {
            Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
            Map<String, Object> location = (Map<String, Object>) geometry.get("location");
            place.setLatitude(((Number) location.get("lat")).doubleValue());
            place.setLongitude(((Number) location.get("lng")).doubleValue());
        } catch (Exception e) {
            log.warn("위경도 정보 추출 실패: {}", place.getGooglePlaceId());
        }

        if (result.containsKey("rating")) {
            place.setRating(((Number) result.get("rating")).doubleValue());
        }
        if (result.containsKey("user_ratings_total")) {
            place.setUserRatingsTotal(((Number) result.get("user_ratings_total")).intValue());
        }
    }
}
