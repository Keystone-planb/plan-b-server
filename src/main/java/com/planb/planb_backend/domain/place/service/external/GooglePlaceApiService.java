package com.planb.planb_backend.domain.place.service.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GooglePlaceApiService {

    @Value("${google.maps.api-key}")
    private String apiKey;

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://maps.googleapis.com/maps/api/place")
            .build();

    /**
     * 구글 Place Details API — 장소 상세 정보(이름, 리뷰, 좌표 등) 조회
     */
    public Map<String, Object> getGooglePlaceDetails(String googlePlaceId) {
        try {
            Map response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/details/json")
                            .queryParam("place_id", googlePlaceId)
                            .queryParam("fields", "name,reviews,rating,user_ratings_total,geometry,types," +
                                    "formatted_phone_number,website,opening_hours,business_status,price_level,formatted_address")
                            .queryParam("language", "ko")
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !response.containsKey("result")) {
                return Collections.emptyMap();
            }
            return (Map<String, Object>) response.get("result");
        } catch (Exception e) {
            log.error("Google Place Details 호출 실패 (ID: {}): {}", googlePlaceId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 구글 Place Details API (경량) — 영업정보만 조회
     * 리뷰/AI 분석 없이 phone, website, opening_hours, price_level만 빠르게 가져옴
     * 추천 최종 5개 장소에 대해서만 호출 (속도 최적화)
     */
    public Map<String, Object> getPlaceBusinessInfo(String googlePlaceId) {
        try {
            Map response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/details/json")
                            .queryParam("place_id", googlePlaceId)
                            .queryParam("fields",
                                    "formatted_phone_number,website,opening_hours,price_level,business_status,formatted_address")
                            .queryParam("language", "ko")
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !response.containsKey("result")) {
                return Collections.emptyMap();
            }
            return (Map<String, Object>) response.get("result");
        } catch (Exception e) {
            log.error("Google 영업정보 조회 실패 (ID: {}): {}", googlePlaceId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 구글 Nearby Search API — 주변 장소 목록 조회 (1차 필터링 포함)
     */
    public List<Map<String, Object>> searchNearbyPlaces(Double lat, Double lng, int radiusMeters, String category) {
        try {
            String googleType = mapToGoogleType(category);
            log.info("Google Nearby Search 실행: location={},{}, radius={}m, googleType={}", lat, lng, radiusMeters, googleType);

            Map response = webClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/nearbysearch/json")
                                .queryParam("location", lat + "," + lng)
                                .queryParam("radius", radiusMeters)
                                .queryParam("language", "ko")
                                .queryParam("key", apiKey);
                        if (googleType != null && !googleType.isEmpty()) {
                            builder.queryParam("type", googleType);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !response.containsKey("results")) {
                log.warn("Google Nearby Search 결과가 없습니다.");
                return Collections.emptyList();
            }
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            return results.stream().limit(20).collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Google Nearby Search 호출 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Google Places Photo API URL 생성
     */
    public String buildPhotoUrl(String photoReference) {
        return "https://maps.googleapis.com/maps/api/place/photo?maxwidth=400&photo_reference="
                + photoReference + "&key=" + apiKey;
    }

    /** PLAN B Enum → 구글 Places API type 변환 */
    private String mapToGoogleType(String category) {
        if (category == null) return "";
        return switch (category.toUpperCase()) {
            case "FOOD"    -> "restaurant";
            case "CAFE"    -> "cafe";
            case "SIGHTS"  -> "tourist_attraction";
            case "SHOP"    -> "shopping_mall";
            case "MARKET"  -> "establishment";
            case "THEME"   -> "amusement_park";
            case "CULTURE" -> "museum";
            case "PARK"    -> "park";
            default        -> "";
        };
    }
}
