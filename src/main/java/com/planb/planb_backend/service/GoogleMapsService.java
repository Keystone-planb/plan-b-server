package com.planb.planb_backend.service;

import com.planb.planb_backend.config.GoogleMapsConfig;
import com.planb.planb_backend.dto.PlaceSearchResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google Places Text Search API 연동 서비스
 * 검색어(query)를 받아 장소 목록을 반환합니다.
 */
@Slf4j
@Service
public class GoogleMapsService {

    private static final String PLACES_BASE_URL = "https://maps.googleapis.com";
    private static final String TEXT_SEARCH_PATH = "/maps/api/place/textsearch/json";

    private final GoogleMapsConfig googleMapsConfig;
    private final WebClient webClient;

    // WebClient.Builder는 Spring Boot가 자동으로 Bean 등록 → 앱 시작 시 1회만 생성
    public GoogleMapsService(GoogleMapsConfig googleMapsConfig, WebClient.Builder webClientBuilder) {
        this.googleMapsConfig = googleMapsConfig;
        this.webClient = webClientBuilder.baseUrl(PLACES_BASE_URL).build();
    }

    /**
     * 장소 검색
     *
     * @param query 검색어 (예: "강남역 카페")
     * @return 정제된 장소 목록
     */
    public List<PlaceSearchResponseDto> searchPlaces(String query) {
        log.info("[GoogleMaps] 장소 검색 요청 - query: {}", query);

        Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(TEXT_SEARCH_PATH)
                        .queryParam("query", query)
                        .queryParam("key", googleMapsConfig.getApiKey())
                        .queryParam("language", "ko")
                        .build())
                .retrieve()
                // 4xx: 잘못된 API 키, 요청 파라미터 오류 등
                .onStatus(status -> status.is4xxClientError(), clientResponse -> {
                    log.error("[GoogleMaps] 클라이언트 오류 - HTTP {}", clientResponse.statusCode());
                    return clientResponse.createException();
                })
                // 5xx: 구글 서버 내부 오류
                .onStatus(status -> status.is5xxServerError(), clientResponse -> {
                    log.error("[GoogleMaps] 구글 서버 오류 - HTTP {}", clientResponse.statusCode());
                    return clientResponse.createException();
                })
                .bodyToMono(Map.class)
                .block();

        // 응답 자체가 없는 경우 (타임아웃 등)
        if (response == null) {
            log.error("[GoogleMaps] 응답이 null입니다.");
            throw new RuntimeException("구글 Maps API 응답이 없습니다.");
        }

        String status = (String) response.get("status");
        log.info("[GoogleMaps] 응답 status: {}", status);

        // ZERO_RESULTS는 정상 케이스 (검색어에 맞는 결과가 없을 뿐)
        if ("ZERO_RESULTS".equals(status)) {
            log.info("[GoogleMaps] 검색 결과 없음 - query: {}", query);
            return Collections.emptyList();
        }

        // OK 외 나머지(OVER_QUERY_LIMIT, REQUEST_DENIED, INVALID_REQUEST 등)는 오류
        if (!"OK".equals(status)) {
            String errorMessage = (String) response.getOrDefault("error_message", "알 수 없는 오류");
            log.error("[GoogleMaps] API 오류 - status: {}, message: {}", status, errorMessage);
            throw new RuntimeException("장소 검색에 실패했습니다. (status: " + status + ")");
        }

        // 결과 파싱 및 DTO 변환
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        return results.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * 구글 API 응답의 단일 장소 데이터를 PlaceSearchResponseDto로 변환
     */
    private PlaceSearchResponseDto toDto(Map<String, Object> result) {
        Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
        Map<String, Object> location = (Map<String, Object>) geometry.get("location");

        double lat = ((Number) location.get("lat")).doubleValue();
        double lng = ((Number) location.get("lng")).doubleValue();

        Double rating = result.get("rating") != null
                ? ((Number) result.get("rating")).doubleValue()
                : null;

        List<String> types = result.get("types") != null
                ? (List<String>) result.get("types")
                : Collections.emptyList();

        return PlaceSearchResponseDto.builder()
                .placeId((String) result.get("place_id"))
                .placeName((String) result.get("name"))
                .address((String) result.get("formatted_address"))
                .lat(lat)
                .lng(lng)
                .rating(rating)
                .types(types)
                .build();
    }
}
