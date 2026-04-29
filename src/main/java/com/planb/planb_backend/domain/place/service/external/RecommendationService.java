package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.BusinessStatus;
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
        // considerNextPlan=true인데 프론트에서 좌표를 안 넘긴 경우, 현재 여행(tripId) 기준으로 DB 자동 조회
        if (context.isConsiderNextPlan() && context.getNextLat() == null && context.getTripId() != null) {
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
            String businessStatusRaw = (String) result.get("business_status");
            if (businessStatusRaw != null && !BusinessStatus.OPERATIONAL.name().equalsIgnoreCase(businessStatusRaw)) {
                log.info(">>>> [영업 상태 탈락] gId={}, status={}", gId, businessStatusRaw);
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
        List<Place> top5 = candidates.stream()
                .sorted((p1, p2) -> Double.compare(
                        scoringStrategy.calculateScore(p2, context),
                        scoringStrategy.calculateScore(p1, context)
                ))
                .limit(5)
                .collect(Collectors.toList());

        // [STEP 7] 상위 5개에 대해서만 영업정보(phone, website, opening_hours) 보강
        // Nearby Search에서 제공하지 않는 필드를 Place Details API로 빠르게 채움
        // 5건 × ~1~2초 = 최대 10초로 타임아웃 위험 없음
        top5.forEach(place -> enrichBusinessInfo(place));

        return top5;
    }

    /**
     * 최종 추천 장소의 영업정보 보강
     * phone, website, 전체 opening_hours 등 Nearby Search에 없는 필드를 Place Details로 채움
     * 이미 채워진 값은 덮어쓰지 않음 (analyze로 저장된 데이터 보존)
     */
    private void enrichBusinessInfo(Place place) {
        if (place.getGooglePlaceId() == null) return;
        try {
            Map<String, Object> details = googlePlaceApiService.getPlaceBusinessInfo(place.getGooglePlaceId());
            if (details == null || details.isEmpty()) return;

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
                    log.warn("알 수 없는 business_status (영업정보 보강): {}", bsRaw);
                }
            }
            // opening_hours 전체 (요일별 시간 포함) — 기존 값이 open_now만 있으면 덮어씀
            if (details.containsKey("opening_hours")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    place.setOpeningHours(mapper.writeValueAsString(details.get("opening_hours")));
                } catch (Exception e) {
                    log.warn("opening_hours 직렬화 실패: {}", place.getGooglePlaceId());
                }
            }
            placeRepository.saveAndFlush(place);
            log.info("[영업정보 보강] {} — phone:{}, website:{}", place.getName(),
                    place.getPhoneNumber() != null ? "있음" : "없음",
                    place.getWebsite() != null ? "있음" : "없음");
        } catch (Exception e) {
            log.warn("[영업정보 보강 실패] {}: {}", place.getName(), e.getMessage());
        }
    }

    /**
     * 다음 목적지 자동 추적
     * - 현재 여행(tripId) 기준으로 오늘 이후 TripPlace 중 현재 시각 이후 첫 번째 일정을 찾아 UserContext에 주입
     * - trip_id 범위로 한정해 다른 여행의 일정이 섞이는 문제 방지
     * - Place 좌표가 DB에 없으면 건너뜀
     */
    private void resolveNextDestination(UserContext context) {
        LocalDate today = LocalDate.now();
        String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        List<TripPlace> upcoming = tripPlaceRepository.findUpcomingByTripId(context.getTripId(), today);

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
     * 구글 Nearby Search 결과로부터 Place 엔티티 정보를 업데이트
     * - Nearby Search가 포함하는 필드: name, geometry, rating, user_ratings_total,
     *   business_status, opening_hours(open_now만), price_level, photos
     * - phone, website, full opening_hours 등은 Place Details API 전용 (analyze 시 저장)
     */
    private void updatePlaceInfo(Place place, Map<String, Object> result) {
        // 이름
        place.setName((String) result.get("name"));

        // 좌표
        try {
            Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
            Map<String, Object> location = (Map<String, Object>) geometry.get("location");
            place.setLatitude(((Number) location.get("lat")).doubleValue());
            place.setLongitude(((Number) location.get("lng")).doubleValue());
        } catch (Exception e) {
            log.warn("위경도 정보 추출 실패: {}", place.getGooglePlaceId());
        }

        // 평점 / 리뷰 수
        if (result.containsKey("rating")) {
            place.setRating(((Number) result.get("rating")).doubleValue());
        }
        if (result.containsKey("user_ratings_total")) {
            place.setUserRatingsTotal(((Number) result.get("user_ratings_total")).intValue());
        }

        // 영업 상태
        if (result.containsKey("business_status")) {
            String bsRaw = (String) result.get("business_status");
            try {
                place.setBusinessStatus(BusinessStatus.valueOf(bsRaw.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("알 수 없는 business_status: {}", bsRaw);
            }
        }

        // 가격대 (0=무료 ~ 4=매우 비쌈)
        if (result.containsKey("price_level")) {
            place.setPriceLevel(((Number) result.get("price_level")).intValue());
        }

        // 영업시간 — Nearby Search는 open_now(현재 영업 여부)만 포함
        // 전체 요일별 시간은 Place Details API(analyze)에서 저장됨
        if (result.containsKey("opening_hours")) {
            try {
                Object openingHours = result.get("opening_hours");
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                place.setOpeningHours(mapper.writeValueAsString(openingHours));
            } catch (Exception e) {
                log.warn("opening_hours 직렬화 실패: {}", place.getGooglePlaceId());
            }
        }

        // 대표 사진 URL
        if (result.containsKey("photos")) {
            try {
                List<Map<String, Object>> photos = (List<Map<String, Object>>) result.get("photos");
                if (photos != null && !photos.isEmpty()) {
                    String photoRef = (String) photos.get(0).get("photo_reference");
                    if (photoRef != null) {
                        place.setPhotoUrl("https://maps.googleapis.com/maps/api/place/photo?maxwidth=400&photoreference=" + photoRef);
                    }
                }
            } catch (Exception e) {
                log.warn("photo_url 추출 실패: {}", place.getGooglePlaceId());
            }
        }
    }
}
