package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final GooglePlaceApiService googlePlaceApiService;
    private final PlaceRepository placeRepository;
    private final ScoringStrategy scoringStrategy;

    @Transactional
    public List<Place> getRecommendations(UserContext context) {

        // [STEP 1] 다음 일정 자동 추적 (동선 가중치용)
        // NOTE: PlanRepository가 현재 구현되지 않아 주석 처리.
        //       다음 일정 좌표는 프론트엔드에서 UserContext.nextLat/nextLng로 직접 전달받아 사용.
        /*
        if (context.isConsiderNextPlan() && context.getNextLat() == null) {
            planRepository.findNextPlan(context.getUserId(), context.getCurrentPlanStartTime())
                    .ifPresent(nextPlan -> {
                        context.setNextLat(nextPlan.getPlace().getLatitude());
                        context.setNextLng(nextPlan.getPlace().getLongitude());
                        log.info("다음 목적지 자동 탐색 완료: {}", nextPlan.getTitle());
                    });
        }
        */

        // [STEP 2] 검색 반경 계산 (isWalk 반영)
        int radiusMeters = context.isWalk() ? (context.getRadiusMinute() * 80) : (context.getRadiusMinute() * 400);
        log.info("검색 반경 설정: {}m (도보여부: {})", radiusMeters, context.isWalk());

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

            // 1. DB에서 기존 장소 조회
            Optional<Place> existingPlaceOpt = placeRepository.findByGooglePlaceId(gId);

            Place place;
            if (existingPlaceOpt.isPresent()) {
                // 2. 이미 있다면 정보 업데이트 (중복 저장 방지)
                place = existingPlaceOpt.get();
                log.info(">>>> [중복 방지] 기존 장소 사용: {}", place.getName());
                updatePlaceInfo(place, result);
                placeRepository.saveAndFlush(place);
            } else {
                // 3. 없다면 기본 정보만 저장 — AI 분석은 하지 않음
                // (AI 분석은 /api/places/{placeId}/analyze 로 별도 요청하거나
                //  추후 백그라운드 배치로 처리. 추천 응답 속도 우선)
                Place newPlace = new Place();
                newPlace.setGooglePlaceId(gId);
                updatePlaceInfo(newPlace, result);
                place = placeRepository.saveAndFlush(newPlace);
                log.info(">>>> [신규 장소 등록] AI 분석 보류: {}", place.getName());
            }

            // [2차 검열 로직] 유저가 요청한 카테고리와 AI 분석 타입 비교
            String requestedCategory = context.getRequestedCategory();
            PlaceType aiDetectedType = place.getType();

            if (requestedCategory != null && aiDetectedType != null) {
                if (!aiDetectedType.name().equalsIgnoreCase(requestedCategory)) {
                    log.info(">>>> [2차 검열 탈락] 장소: {}, 요청타입: {}, AI분석타입: {} -> 추천 제외",
                            place.getName(), requestedCategory, aiDetectedType);
                    continue;
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
