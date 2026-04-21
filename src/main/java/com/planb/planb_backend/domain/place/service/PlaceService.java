package com.planb.planb_backend.domain.place.service;

import com.planb.planb_backend.domain.place.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google Places API + OpenAI API 연동 서비스
 */
@Slf4j
@Service
public class PlaceService {

    private static final String PLACES_BASE_URL = "https://maps.googleapis.com";
    private static final String DETAILS_PATH = "/maps/api/place/details/json";

    // 실제 키 미설정 시 서버 시작 실패를 막기 위해 빈 문자열 기본값 사용
    @Value("${google.places-api-key:}")
    private String googlePlacesApiKey;

    @Value("${openai.api-key:}")
    private String openaiApiKey;

    private final WebClient webClient;

    // WebClient.Builder는 Spring Boot가 자동으로 Bean 등록 → 앱 시작 시 1회만 생성
    public PlaceService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(PLACES_BASE_URL).build();
    }

    /**
     * GET /api/places/search?query=&lat=&lng=
     * 장소 검색 (Google Places Text Search API 예정)
     */
    public PlaceSearchResponse searchPlaces(String query, double lat, double lng) {
        log.info("[Place] 장소 검색 요청 - query: {}, lat: {}, lng: {}", query, lat, lng);

        // TODO: Google Places Text Search API 연동
        return PlaceSearchResponse.builder()
                .places(List.of(
                        PlaceSearchResponse.PlaceItem.builder()
                                .placeId("mock-place-001")
                                .name(query + " 카페")
                                .address("서울특별시 강남구 테헤란로 123")
                                .rating(4.5)
                                .category("CAFE")
                                .build(),
                        PlaceSearchResponse.PlaceItem.builder()
                                .placeId("mock-place-002")
                                .name(query + " 식당")
                                .address("서울특별시 강남구 테헤란로 456")
                                .rating(4.2)
                                .category("FOOD")
                                .build()
                ))
                .build();
    }

    /**
     * GET /api/places/{placeId}
     * 장소 상세 정보 조회 (Google Places Details API)
     * fields 파라미터로 필요한 데이터만 지정 → 과금 최적화
     */
    public PlaceDetailResponse getPlaceDetail(String placeId) {
        log.info("[Place] 장소 상세 조회 - placeId: {}", placeId);

        Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(DETAILS_PATH)
                        .queryParam("place_id", placeId)
                        .queryParam("fields", "name,formatted_address,rating,reviews,opening_hours,geometry")
                        .queryParam("key", googlePlacesApiKey)
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
        return toDetailDto(placeId, result);
    }

    /**
     * 구글 API 응답의 result 객체를 PlaceDetailResponse로 변환
     */
    private PlaceDetailResponse toDetailDto(String placeId, Map<String, Object> result) {
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

        return PlaceDetailResponse.builder()
                .placeId(placeId)
                .name((String) result.get("name"))
                .address((String) result.get("formatted_address"))
                .rating(rating)
                .openingHours(openingHours)
                .lat(lat)
                .lng(lng)
                .reviews(reviews)
                .build();
    }

    /**
     * GET /api/places/{placeId}/summary
     * 장소 AI 요약 (OpenAI API 예정)
     */
    public PlaceSummaryResponse getPlaceSummary(String placeId) {
        log.info("[Place] AI 요약 요청 - placeId: {}", placeId);

        // TODO: OpenAI API 연동 (리뷰 수집 후 요약)
        return PlaceSummaryResponse.builder()
                .placeId(placeId)
                .aiSummary("분위기 있는 인테리어와 친절한 직원으로 유명한 카페입니다. 커피 퀄리티가 높고 디저트도 맛있습니다.")
                .keywords(List.of("감성 인테리어", "친절한 직원", "커피 맛집", "디저트 추천"))
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
