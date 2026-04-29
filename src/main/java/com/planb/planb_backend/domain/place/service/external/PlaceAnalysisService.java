package com.planb.planb_backend.domain.place.service.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.place.entity.BusinessStatus;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.entity.Space;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceAnalysisService {

    private final PlaceRepository placeRepository;
    private final OpenAiAnalysisService openAiAnalysisService;
    private final GooglePlaceApiService googlePlaceApiService;
    private final InstaApiService instaApiService;
    private final NaverApiService naverApiService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 특정 장소에 대한 AI 리뷰 분석 실행 및 결과 객체 반환
     */
    @Transactional
    public Place processPlaceAnalysis(Long placeId) throws Exception {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다."));

        log.info("======= [START ANALYSIS] Place ID: {} =======", placeId);

        // [사전 검증] google_place_id 없으면 즉시 중단
        if (place.getGooglePlaceId() == null || place.getGooglePlaceId().isBlank()) {
            throw new IllegalStateException(
                "Place(id=" + placeId + ")에 google_place_id가 없습니다. 분석 전에 google_place_id를 먼저 채워야 합니다.");
        }

        // [STEP 1] 구글 상세 정보 가져오기
        Map<String, Object> googleDetails = googlePlaceApiService.getGooglePlaceDetails(place.getGooglePlaceId());

        if (googleDetails == null || googleDetails.isEmpty()) {
            throw new IllegalStateException(
                "구글 Place Details 조회 실패. (place_id=" + place.getGooglePlaceId() + ") " +
                "Naver/Insta/AI 호출은 모두 건너뜁니다.");
        }

        // [STEP 1.5] 이름 검증
        String googleName = (String) googleDetails.get("name");
        if (googleName == null || googleName.isBlank()) {
            throw new IllegalStateException(
                "구글이 응답을 줬지만 name이 비어있습니다. (place_id=" + place.getGooglePlaceId() + ")");
        }

        // [STEP 2] 기초 정보 업데이트 및 DB 저장 (이름/위경도 확정)
        updateBasicInfo(place, googleDetails);
        log.info(">>>> [INFO] 기초 정보 확정 완료 - 이름: {}, 좌표: {}, {}",
                place.getName(), place.getLatitude(), place.getLongitude());

        // [STEP 3] 플랫폼별 리뷰 통합 수집
        Map<String, List<String>> allReviews = collectAllReviews(place, googleDetails);

        log.info("======= [AI INPUT DATA CHECK] =======");
        log.info("대상 장소명(확정): {}", place.getName());
        log.info("대상 카테고리: {}", place.getCategory());
        allReviews.forEach((platform, reviews) -> {
            log.info("플랫폼: [{}] | 수집된 리뷰 개수: {}개", platform, reviews.size());
            if (!reviews.isEmpty()) {
                log.info("플랫폼: [{}] | 첫 번째 리뷰 샘플: {}...", platform,
                        reviews.get(0).substring(0, Math.min(reviews.get(0).length(), 50)));
            }
        });
        log.info("=====================================");

        // [STEP 4] AI 분석 호출
        log.info(">>>> AI 분석 서비스 호출 중...");
        Map<String, Object> aiResponse = openAiAnalysisService.requestAnalysis(
                place.getName(),
                place.getCategory(),
                allReviews
        );

        log.info(">>>> AI 응답 수신 완료: {}", aiResponse);

        // [STEP 5] 분석 결과 최종 매핑 및 저장
        return updatePlaceAnalysisData(place, aiResponse);
    }

    private void updateBasicInfo(Place place, Map<String, Object> details) {
        if (details.isEmpty()) return;

        String googleName = (String) details.get("name");
        if (googleName != null && !googleName.isEmpty()) {
            place.setName(googleName);
        }

        List<String> googleTypes = (List<String>) details.get("types");
        if (googleTypes != null && !googleTypes.isEmpty()) {
            place.setCategory(determineBestCategory(googleTypes));
        }

        if (details.containsKey("rating")) {
            place.setRating(((Number) details.get("rating")).doubleValue());
        }
        if (details.containsKey("user_ratings_total")) {
            place.setUserRatingsTotal((Integer) details.get("user_ratings_total"));
        }
        if (details.containsKey("geometry")) {
            Map<String, Object> geometry = (Map<String, Object>) details.get("geometry");
            if (geometry != null && geometry.containsKey("location")) {
                Map<String, Object> locMap = (Map<String, Object>) geometry.get("location");
                place.setLatitude(((Number) locMap.get("lat")).doubleValue());
                place.setLongitude(((Number) locMap.get("lng")).doubleValue());
            }
        }

        // 영업 정보 저장
        String bsRaw = (String) details.getOrDefault("business_status", null);
        if (bsRaw != null) {
            try {
                place.setBusinessStatus(BusinessStatus.valueOf(bsRaw.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn(">>>> 알 수 없는 business_status: {}", bsRaw);
            }
        }
        // 한국어 주소 (formatted_address 우선, 없으면 vicinity)
        String formattedAddress = (String) details.get("formatted_address");
        String vicinity = (String) details.get("vicinity");
        if (formattedAddress != null && !formattedAddress.isBlank()) {
            place.setAddress(formattedAddress);
        } else if (vicinity != null && !vicinity.isBlank()) {
            place.setAddress(vicinity);
        }

        place.setPhoneNumber((String) details.getOrDefault("formatted_phone_number", null));
        place.setWebsite((String) details.getOrDefault("website", null));
        if (details.containsKey("price_level")) {
            place.setPriceLevel(((Number) details.get("price_level")).intValue());
        }
        if (details.containsKey("opening_hours")) {
            try {
                place.setOpeningHours(objectMapper.writeValueAsString(details.get("opening_hours")));
                log.info(">>>> 영업시간 저장 완료: {}", place.getName());
            } catch (Exception e) {
                log.warn(">>>> opening_hours 직렬화 실패: {}", e.getMessage());
            }
        }

        placeRepository.saveAndFlush(place);
    }

    private Map<String, List<String>> collectAllReviews(Place place, Map<String, Object> googleDetails) {
        Map<String, List<String>> allReviews = new HashMap<>();
        log.info("======= [DATA COLLECTION DEBUG] =======");

        // 1. Google 리뷰 수집
        List<String> googleTexts = new ArrayList<>();
        if (googleDetails.containsKey("reviews")) {
            List<Map<String, Object>> reviewsList = (List<Map<String, Object>>) googleDetails.get("reviews");
            if (reviewsList != null) {
                googleTexts = reviewsList.stream()
                        .map(r -> (String) r.get("text"))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        }
        if (googleTexts.isEmpty()) googleTexts.add("데이터 없음");
        allReviews.put("Google", googleTexts);
        log.info(">>>> Google 리뷰 수집 완료: {}건", (googleTexts.contains("데이터 없음") ? 0 : googleTexts.size()));

        // 2. Instagram 리뷰 수집
        List<String> instaTexts = new ArrayList<>();
        if (place.getLatitude() != null && place.getLongitude() != null) {
            try {
                instaTexts = instaApiService.getInstagramReviews(
                        place.getName(), place.getLatitude(), place.getLongitude());
            } catch (Exception e) {
                log.error(">>>> Instagram 수집 중 에러: {}", e.getMessage());
            }
        }
        if (instaTexts == null || instaTexts.isEmpty()) {
            instaTexts = new ArrayList<>(List.of("데이터 없음"));
            log.info(">>>> Instagram 리뷰 결과 없음");
        } else {
            log.info(">>>> Instagram 리뷰 수집 완료: {}건", instaTexts.size());
        }
        allReviews.put("Instagram", instaTexts);

        // 3. Naver 리뷰 수집
        List<String> naverTexts = new ArrayList<>();
        try {
            naverTexts = naverApiService.getNaverReviews(place.getName());
        } catch (Exception e) {
            log.error(">>>> Naver 수집 중 에러: {}", e.getMessage());
        }
        if (naverTexts == null || naverTexts.isEmpty()) {
            naverTexts = new ArrayList<>(List.of("데이터 없음"));
            log.info(">>>> Naver 리뷰 결과 없음");
        } else {
            log.info(">>>> Naver 리뷰 수집 완료: {}건", naverTexts.size());
        }
        allReviews.put("Naver", naverTexts);

        log.info("=======================================");
        return allReviews;
    }

    private String determineBestCategory(List<String> types) {
        if (types == null || types.isEmpty()) return "미분류";
        List<String> priorityOrder = List.of(
                "cafe", "bakery", "restaurant", "bar", "meal_takeaway",
                "tourist_attraction", "museum", "art_gallery", "aquarium", "zoo",
                "shopping_mall", "department_store", "clothing_store", "market",
                "amusement_park", "theme_park", "movie_theater",
                "park", "natural_feature", "beach"
        );
        for (String priority : priorityOrder) {
            if (types.contains(priority)) return priority;
        }
        return types.get(0);
    }

    private Place updatePlaceAnalysisData(Place place, Map<String, Object> ai) throws Exception {
        if (ai == null || ai.isEmpty()) return setFallbackValues(place);

        try {
            if (ai.get("space") != null) {
                place.setSpace(Space.valueOf(ai.get("space").toString().toUpperCase()));
            }
            if (ai.get("type") != null) {
                String typeStr = ai.get("type").toString().toUpperCase().replace(" ", "_");
                try {
                    if (typeStr.equals("BAR")) typeStr = "FOOD";
                    place.setType(PlaceType.valueOf(typeStr));
                } catch (Exception e) {
                    place.setType(PlaceType.FOOD);
                }
            }
            if (ai.get("mood") != null) {
                try {
                    place.setMood(Mood.valueOf(ai.get("mood").toString().toUpperCase()));
                } catch (Exception e) {
                    place.setMood(Mood.LOCAL);
                }
            }

            Map<String, Object> reviewCache = new HashMap<>();
            reviewCache.put("platformSummaries", ai.get("summaries"));
            reviewCache.put("totalSummary", ai.get("review_data"));

            place.setReviewData(objectMapper.writeValueAsString(reviewCache));
            place.setLastSyncedAt(LocalDateTime.now());
            log.info(">>>> 최종 DB 업데이트 성공: {}", place.getName());

        } catch (Exception e) {
            log.error(">>>> AI 데이터 매핑 실패: {}", e.getMessage());
            setFallbackValues(place);
        }
        return placeRepository.saveAndFlush(place);
    }

    private Place setFallbackValues(Place place) {
        log.info(">>>> [FALLBACK] 기본값으로 데이터 설정");
        if (place.getSpace() == null) place.setSpace(Space.MIX);
        if (place.getType() == null) place.setType(PlaceType.FOOD);
        if (place.getMood() == null) place.setMood(Mood.LOCAL);
        place.setLastSyncedAt(LocalDateTime.now());
        return placeRepository.saveAndFlush(place);
    }
}
