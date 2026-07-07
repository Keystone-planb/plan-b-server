package com.planb.planb_backend.domain.place.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.config.GoogleMapsConfig;
import com.planb.planb_backend.domain.place.dto.*;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.external.PlaceAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
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

        // 호출 1: 일반 검색 (지역구, 일반 장소 등)
        List<Map<String, Object>> generalResults = fetchTextSearchResults(query, null);

        // 호출 2: tourist_attraction 타입 필터 검색 (관광명소)
        List<Map<String, Object>> attractionResults = fetchTextSearchResults(query, "tourist_attraction");

        // 두 결과 합치기 — placeId 기준 중복 제거 (일반 검색 결과 순서 우선)
        Map<String, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : generalResults) {
            String placeId = (String) r.get("place_id");
            if (placeId != null) merged.put(placeId, r);
        }
        for (Map<String, Object> r : attractionResults) {
            String placeId = (String) r.get("place_id");
            if (placeId != null) merged.putIfAbsent(placeId, r);
        }

        log.info("[Place] 검색 결과 — 일반: {}건, 관광명소: {}건, 합산(중복제거): {}건",
                generalResults.size(), attractionResults.size(), merged.size());

        List<PlaceSearchResponse.PlaceItem> places = merged.values().stream()
                .map(this::toPlaceItem)
                .collect(Collectors.toList());

        return PlaceSearchResponse.builder().places(places).build();
    }

    /**
     * Google Places Text Search API 단일 호출
     * type 파라미터가 null이면 타입 필터 없이 호출
     */
    private List<Map<String, Object>> fetchTextSearchResults(String query, String type) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path(TEXT_SEARCH_PATH)
                                .queryParam("query", query)
                                .queryParam("key", googleMapsConfig.getApiKey())
                                .queryParam("language", "ko");
                        if (type != null) builder.queryParam("type", type);
                        return builder.build();
                    })
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), clientResponse -> {
                        log.error("[Place] 장소 검색 클라이언트 오류 (type={}) - HTTP {}", type, clientResponse.statusCode());
                        return clientResponse.createException();
                    })
                    .onStatus(status -> status.is5xxServerError(), clientResponse -> {
                        log.error("[Place] 장소 검색 구글 서버 오류 (type={}) - HTTP {}", type, clientResponse.statusCode());
                        return clientResponse.createException();
                    })
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return Collections.emptyList();

            String status = (String) response.get("status");
            if (!"OK".equals(status) && !"ZERO_RESULTS".equals(status)) {
                log.warn("[Place] 장소 검색 API 오류 (type={}) - status: {}", type, status);
                return Collections.emptyList();
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            return results != null ? results : Collections.emptyList();

        } catch (Exception e) {
            log.warn("[Place] 장소 검색 호출 실패 (type={}) - {}", type, e.getMessage());
            return Collections.emptyList();
        }
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
    @Cacheable(value = "placeDetail", key = "#placeId")
    public PlaceDetailResponse getPlaceDetail(String placeId) {
        log.info("[Place] 장소 상세 조회 - placeId: {}", placeId);
        // API 키 로드 확인 (보안상 앞 5자리만 출력)
        log.info("[Place] API 키 로드 확인 - 길이: {}, 앞자리: {}",
                googleMapsConfig.getApiKey().length(),
                googleMapsConfig.getApiKey().length() >= 5 ? googleMapsConfig.getApiKey().substring(0, 5) : "짧음(비정상)");

        Map<String, Object> response;
        try {
            response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(DETAILS_PATH)
                            .queryParam("place_id", placeId)
                            .queryParam("fields", "name,formatted_address,rating,reviews,opening_hours,geometry")
                            .queryParam("key", googleMapsConfig.getApiKey())
                            .queryParam("language", "ko")
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), clientResponse -> {
                        log.warn("[Place] 구글 API 4xx - placeId: {}, HTTP {}", placeId, clientResponse.statusCode());
                        return clientResponse.createException();
                    })
                    .onStatus(status -> status.is5xxServerError(), clientResponse -> {
                        log.warn("[Place] 구글 API 5xx - placeId: {}, HTTP {}", placeId, clientResponse.statusCode());
                        return clientResponse.createException();
                    })
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.warn("[Place] 장소 상세 조회 실패 - placeId: {}, error: {}", placeId, e.getMessage());
            throw new IllegalArgumentException("장소 정보를 가져올 수 없습니다. (placeId: " + placeId + ")");
        }

        if (response == null) {
            log.warn("[Place] 장소 상세 응답이 null - placeId: {}", placeId);
            throw new IllegalArgumentException("장소 정보를 가져올 수 없습니다. (placeId: " + placeId + ")");
        }

        String status = (String) response.get("status");
        log.info("[Place] 응답 status: {}", status);

        if ("NOT_FOUND".equals(status)) {
            log.warn("[Place] 장소를 찾을 수 없음 - placeId: {}", placeId);
            throw new IllegalArgumentException("해당 장소를 찾을 수 없습니다. (placeId: " + placeId + ")");
        }

        if (!"OK".equals(status)) {
            String errorMessage = (String) response.getOrDefault("error_message", "알 수 없는 오류");
            log.warn("[Place] API 오류 - status: {}, message: {}", status, errorMessage);
            throw new IllegalArgumentException("장소 상세 조회에 실패했습니다. (status: " + status + ")");
        }

        Map<String, Object> result = (Map<String, Object>) response.get("result");

        // DB에서 AI 분석 태그 조회 (분석 미완료 장소는 Optional.empty)
        Optional<Place> dbPlace = placeRepository.findByGooglePlaceId(placeId);

        // 최초 조회 시 or DB에 있지만 분석 미완료(space=null)인 경우 백그라운드 분석 자동 트리거
        boolean needsAnalysis = dbPlace.isEmpty()
                || dbPlace.map(p -> p.getSpace() == null).orElse(false);
        if (needsAnalysis) {
            log.info("[Place] 분석 트리거 — {} (DB미등록={}, space미완료={})",
                    placeId, dbPlace.isEmpty(),
                    dbPlace.map(p -> p.getSpace() == null).orElse(false));
            placeAnalysisService.triggerAnalysisAsync(placeId);
        }

        return toDetailDto(placeId, result, dbPlace);
    }

    /**
     * 구글 API 응답의 result 객체를 PlaceDetailResponse로 변환
     * dbPlace: AI 분석 태그(space/type/mood) 병합용. 미분석 장소는 Optional.empty()
     */
    private PlaceDetailResponse toDetailDto(String placeId, Map<String, Object> result, Optional<Place> dbPlace) {
        // 위경도 추출 — geometry 누락 시 500 대신 명확한 400 반환
        Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
        if (geometry == null) {
            throw new IllegalStateException("구글 API 응답에 좌표(geometry) 정보가 없습니다. (placeId: " + placeId + ")");
        }
        Map<String, Object> location = (Map<String, Object>) geometry.get("location");
        if (location == null) {
            throw new IllegalStateException("구글 API 응답에 location 정보가 없습니다. (placeId: " + placeId + ")");
        }
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
        boolean analyzed  = dbPlace.map(p -> p.getSpace() != null).orElse(false); // space != null = 분석 완료 (리뷰 없어도)

        return PlaceSummaryResponse.builder()
                .placeId(placeId)
                .analyzed(analyzed)
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
     * GET /api/places/{placeId}/analysis-status
     * AI 분석 완료 여부 확인
     * PENDING  : DB에 레코드가 없거나 space 미분석 상태
     * COMPLETE : space/type/mood 분석 결과 존재
     */
    public PlaceAnalysisStatusResponse getAnalysisStatus(String placeId) {
        boolean complete = placeRepository.findByGooglePlaceId(placeId)
                .map(p -> p.getSpace() != null)
                .orElse(false);

        return PlaceAnalysisStatusResponse.builder()
                .placeId(placeId)
                .status(complete ? "COMPLETE" : "PENDING")
                .build();
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
