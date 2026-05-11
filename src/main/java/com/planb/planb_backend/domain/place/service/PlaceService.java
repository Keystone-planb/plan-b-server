package com.planb.planb_backend.domain.place.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.config.GoogleMapsConfig;
import com.planb.planb_backend.domain.place.dto.*;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.external.PlaceAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Google Places API + OpenAI API 연동 서비스
 */
@Slf4j
@Service
public class PlaceService {

    private static final String PLACES_BASE_URL = "https://maps.googleapis.com";
    private static final String TEXT_SEARCH_PATH = "/maps/api/place/textsearch/json";
    private static final String DETAILS_PATH = "/maps/api/place/details/json";

    @Value("${openai.api-key:}")
    private String openaiApiKey;

    private final GoogleMapsConfig googleMapsConfig; // google.maps.api-key 사용
    private final WebClient webClient;
    private final PlaceRepository placeRepository;
    private final PlaceAnalysisService placeAnalysisService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlaceService(GoogleMapsConfig googleMapsConfig, WebClient.Builder webClientBuilder,
                        PlaceRepository placeRepository, PlaceAnalysisService placeAnalysisService) {
        this.googleMapsConfig = googleMapsConfig;
        this.webClient = webClientBuilder.baseUrl(PLACES_BASE_URL).build();
        this.placeRepository = placeRepository;
        this.placeAnalysisService = placeAnalysisService;
    }

    /**
     * GET /api/places/search?query=&lat=&lng=
     * 장소 검색 (Google Places Text Search API)
     */
    public PlaceSearchResponse searchPlaces(String query) {
        log.info("[Place] 장소 검색 요청 - query: {}", query);

        Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(TEXT_SEARCH_PATH)
                        .queryParam("query", query)
                        .queryParam("key", googleMapsConfig.getApiKey())
                        .queryParam("language", "ko")
                        .build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), clientResponse -> {
                    log.error("[Place] 장소 검색 클라이언트 오류 - HTTP {}", clientResponse.statusCode());
                    return clientResponse.createException();
                })
                .onStatus(status -> status.is5xxServerError(), clientResponse -> {
                    log.error("[Place] 장소 검색 구글 서버 오류 - HTTP {}", clientResponse.statusCode());
                    return clientResponse.createException();
                })
                .bodyToMono(Map.class)
                .block();

        if (response == null) {
            log.error("[Place] 장소 검색 응답이 null - query: {}", query);
            throw new RuntimeException("구글 Places API 응답이 없습니다.");
        }

        String status = (String) response.get("status");
        log.info("[Place] 장소 검색 응답 status: {}", status);

        if ("ZERO_RESULTS".equals(status)) {
            log.info("[Place] 검색 결과 없음 - query: {}", query);
            return PlaceSearchResponse.builder().places(Collections.emptyList()).build();
        }

        if (!"OK".equals(status)) {
            String errorMessage = (String) response.getOrDefault("error_message", "알 수 없는 오류");
            log.error("[Place] 장소 검색 API 오류 - status: {}, message: {}", status, errorMessage);
            throw new RuntimeException("장소 검색에 실패했습니다. (status: " + status + ")");
        }

        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        if (results == null || results.isEmpty()) {
            return PlaceSearchResponse.builder().places(Collections.emptyList()).build();
        }

        List<PlaceSearchResponse.PlaceItem> places = results.stream()
                .map(this::toPlaceItem)
                .collect(Collectors.toList());

        return PlaceSearchResponse.builder().places(places).build();
    }

    /**
     * 구글 API 응답의 단일 결과를 PlaceItem으로 변환
     */
    private PlaceSearchResponse.PlaceItem toPlaceItem(Map<String, Object> result) {
        List<String> types = result.get("types") != null
                ? (List<String>) result.get("types")
                : Collections.emptyList();
        // types 배열의 첫 번째 값을 카테고리로 사용 (예: restaurant → RESTAURANT)
        String category = types.isEmpty() ? "UNKNOWN" : types.get(0).toUpperCase();

        double rating = result.get("rating") != null
                ? ((Number) result.get("rating")).doubleValue()
                : 0.0;

        return PlaceSearchResponse.PlaceItem.builder()
                .placeId((String) result.get("place_id"))
                .name((String) result.get("name"))
                .address((String) result.get("formatted_address"))
                .rating(rating)
                .category(category)
                .build();
    }

    /**
     * GET /api/places/{placeId}
     * 장소 상세 정보 조회 (Google Places Details API)
     * fields 파라미터로 필요한 데이터만 지정 → 과금 최적화
     */
    public PlaceDetailResponse getPlaceDetail(String placeId) {
        log.info("[Place] 장소 상세 조회 - placeId: {}", placeId);
        // API 키 로드 확인 (보안상 앞 5자리만 출력)
        log.info("[Place] API 키 로드 확인 - 길이: {}, 앞자리: {}",
                googleMapsConfig.getApiKey().length(),
                googleMapsConfig.getApiKey().length() >= 5 ? googleMapsConfig.getApiKey().substring(0, 5) : "짧음(비정상)");

        Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(DETAILS_PATH)
                        .queryParam("place_id", placeId)
                        .queryParam("fields", "name,formatted_address,rating,reviews,opening_hours,geometry")
                        .queryParam("key", googleMapsConfig.getApiKey())
                        .queryParam("language", "ko")
                        .build())
                .retrieve()
                // 4xx: 잘못된 API 키, 파라미터 오류 등
                .onStatus(status -> status.is4xxClientError(), clientResponse -> {
                    log.error("[Place] 클라이언트 오류 - HTTP {}", clientResponse.statusCode());
                    return clientResponse.createException();
                })
                // 5xx: 구글 서버 내부 오류
                .onStatus(status -> status.is5xxServerError(), clientResponse -> {
                    log.error("[Place] 구글 서버 오류 - HTTP {}", clientResponse.statusCode());
                    return clientResponse.createException();
                })
                .bodyToMono(Map.class)
                .block();

        if (response == null) {
            log.error("[Place] 장소 상세 응답이 null - placeId: {}", placeId);
            throw new RuntimeException("구글 Places API 응답이 없습니다.");
        }

        String status = (String) response.get("status");
        log.info("[Place] 응답 status: {}", status);

        if ("NOT_FOUND".equals(status)) {
            log.warn("[Place] 장소를 찾을 수 없음 - placeId: {}", placeId);
            throw new RuntimeException("해당 장소를 찾을 수 없습니다. (placeId: " + placeId + ")");
        }

        if (!"OK".equals(status)) {
            String errorMessage = (String) response.getOrDefault("error_message", "알 수 없는 오류");
            log.error("[Place] API 오류 - status: {}, message: {}", status, errorMessage);
            throw new RuntimeException("장소 상세 조회에 실패했습니다. (status: " + status + ")");
        }

        Map<String, Object> result = (Map<String, Object>) response.get("result");

        // DB에서 AI 분석 태그 조회 (분석 미완료 장소는 Optional.empty)
        Optional<Place> dbPlace = placeRepository.findByGooglePlaceId(placeId);

        // 최초 조회 시 백그라운드 분석 자동 트리거 (두 번째 조회부터 AI 필드 채워짐)
        if (dbPlace.isEmpty()) {
            log.info("[Place] DB 미등록 장소 — 자동 분석 시작: {}", placeId);
            placeAnalysisService.triggerAnalysisAsync(placeId);
        }

        return toDetailDto(placeId, result, dbPlace);
    }

    /**
     * 구글 API 응답의 result 객체를 PlaceDetailResponse로 변환
     * dbPlace: AI 분석 태그(space/type/mood) 병합용. 미분석 장소는 Optional.empty()
     */
    private PlaceDetailResponse toDetailDto(String placeId, Map<String, Object> result, Optional<Place> dbPlace) {
        // 위경도 추출
        Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
        Map<String, Object> location = (Map<String, Object>) geometry.get("location");
        double lat = ((Number) location.get("lat")).doubleValue();
        double lng = ((Number) location.get("lng")).doubleValue();

        // 영업시간: 요일별 텍스트 배열 → " / "로 이어붙임
        String openingHours = null;
        if (result.get("opening_hours") != null) {
            Map<String, Object> hours = (Map<String, Object>) result.get("opening_hours");
            List<String> weekdayText = (List<String>) hours.get("weekday_text");
            if (weekdayText != null && !weekdayText.isEmpty()) {
                openingHours = String.join(" / ", weekdayText);
            }
        }

        // 리뷰 목록: AI 분석용이므로 text 원문 그대로 보존
        List<PlaceDetailResponse.Review> reviews = Collections.emptyList();
        if (result.get("reviews") != null) {
            List<Map<String, Object>> rawReviews = (List<Map<String, Object>>) result.get("reviews");
            reviews = rawReviews.stream()
                    .map(r -> PlaceDetailResponse.Review.builder()
                            .text((String) r.getOrDefault("text", ""))
                            .rating(r.get("rating") != null ? ((Number) r.get("rating")).intValue() : 0)
                            .relativeTimeDescription((String) r.getOrDefault("relative_time_description", ""))
                            .build())
                    .collect(Collectors.toList());
        }

        Double rating = result.get("rating") != null
                ? ((Number) result.get("rating")).doubleValue()
                : null;

        String reviewData = dbPlace.map(Place::getReviewData).orElse(null);

        return PlaceDetailResponse.builder()
                .placeId(placeId)
                .name((String) result.get("name"))
                .address((String) result.get("formatted_address"))
                .rating(rating)
                .openingHours(openingHours)
                .lat(lat)
                .lng(lng)
                .reviews(reviews)
                .space(dbPlace.map(p -> p.getSpace() != null ? p.getSpace().name() : null).orElse(null))
                .type(dbPlace.map(p -> p.getType() != null ? p.getType().name() : null).orElse(null))
                .mood(dbPlace.map(p -> p.getMood() != null ? p.getMood().name() : null).orElse(null))
                .reviewSummary(extractTotalSummary(reviewData))
                .googleReview(extractPlatformSummary(reviewData, "Google"))
                .naverReview(extractPlatformSummary(reviewData, "Naver"))
                .instaReview(extractPlatformSummary(reviewData, "Instagram"))
                .build();
    }

    /**
     * GET /api/places/{placeId}/summary
     * 장소 AI 요약 — DB reviewData에서 totalSummary + 플랫폼별 요약 반환
     * AI 분석이 완료된 장소는 실데이터, 미분석 장소는 null 반환
     */
    public PlaceSummaryResponse getPlaceSummary(String placeId) {
        log.info("[Place] AI 요약 요청 - placeId: {}", placeId);

        Optional<Place> dbPlace = placeRepository.findByGooglePlaceId(placeId);
        String reviewData = dbPlace.map(Place::getReviewData).orElse(null);

        return PlaceSummaryResponse.builder()
                .placeId(placeId)
                .aiSummary(extractTotalSummary(reviewData))
                .googleReview(extractPlatformSummary(reviewData, "Google"))
                .naverReview(extractPlatformSummary(reviewData, "Naver"))
                .instaReview(extractPlatformSummary(reviewData, "Instagram"))
                .build();
    }

    // ───────────────────────────────────────────
    //  reviewData JSONB 파싱 헬퍼
    //  DB 저장 구조: {"totalSummary":"...", "platformSummaries":{"Google":"...","Naver":"...","Instagram":"..."}}
    // ───────────────────────────────────────────

    private String extractTotalSummary(String reviewData) {
        if (reviewData == null || reviewData.isBlank()) return null;
        try {
            Map<?, ?> map = objectMapper.readValue(reviewData, Map.class);
            Object val = map.get("totalSummary");
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            log.warn("[Place] totalSummary 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private String extractPlatformSummary(String reviewData, String platform) {
        if (reviewData == null || reviewData.isBlank()) return null;
        try {
            Map<?, ?> map = objectMapper.readValue(reviewData, Map.class);
            Object summaries = map.get("platformSummaries");
            if (!(summaries instanceof Map)) return null;
            Object val = ((Map<?, ?>) summaries).get(platform);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            log.warn("[Place] platformSummary({}) 파싱 실패: {}", platform, e.getMessage());
            return null;
        }
    }

    /**
     * GET /api/places/{placeId}/freshness
     * 장소 정보 최신성 확인 (운영 중단 여부 등)
     */
    public PlaceFreshnessResponse getPlaceFreshness(String placeId) {
        log.info("[Place] 최신성 확인 요청 - placeId: {}", placeId);

        // TODO: Google Places API + OpenAI 조합하여 운영 여부 판단
        return PlaceFreshnessResponse.builder()
                .placeId(placeId)
                .lastUpdated("2026-04-10")
                .status("FRESH")
                .confidenceScore(0.92)
                .build();
    }
}
