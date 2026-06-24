package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.trip.entity.TransportMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
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

    private final WebClient distanceMatrixClient = WebClient.builder()
            .baseUrl("https://maps.googleapis.com/maps/api/distancematrix")
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
                    .block(Duration.ofSeconds(10));

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
                    .block(Duration.ofSeconds(10));

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
                    .block(Duration.ofSeconds(10));

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

    /**
     * Google Distance Matrix API — 두 지점 간 실제 이동시간(분) 조회
     * 장소 교체 확정 시 뒤 일정 시간 재계산에만 사용 (비용·속도 최적화)
     * 실패 시 TransportMode 고정 속도(Haversine 직선거리)로 폴백
     */
    public int getTravelTimeMinutes(double fromLat, double fromLng,
                                    double toLat, double toLng,
                                    TransportMode mode) {
        try {
            String travelMode = switch (mode) {
                case TRANSIT -> "transit";
                case CAR     -> "driving";
                default      -> "walking";
            };

            Map response = distanceMatrixClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/json")
                            .queryParam("origins", fromLat + "," + fromLng)
                            .queryParam("destinations", toLat + "," + toLng)
                            .queryParam("mode", travelMode)
                            .queryParam("language", "ko")
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));

            if (response == null) return fallbackTravelMinutes(fromLat, fromLng, toLat, toLng, mode);

            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
            if (rows == null || rows.isEmpty()) return fallbackTravelMinutes(fromLat, fromLng, toLat, toLng, mode);

            List<Map<String, Object>> elements = (List<Map<String, Object>>) rows.get(0).get("elements");
            if (elements == null || elements.isEmpty()) return fallbackTravelMinutes(fromLat, fromLng, toLat, toLng, mode);

            Map<String, Object> element = elements.get(0);
            if (!"OK".equals(element.get("status"))) return fallbackTravelMinutes(fromLat, fromLng, toLat, toLng, mode);

            Map<String, Object> duration = (Map<String, Object>) element.get("duration");
            int seconds = ((Number) duration.get("value")).intValue();
            int minutes = (int) Math.ceil(seconds / 60.0);
            log.info("[DistanceMatrix] {},{} → {},{} mode={} → {}분", fromLat, fromLng, toLat, toLng, travelMode, minutes);
            return minutes;

        } catch (Exception e) {
            log.warn("[DistanceMatrix] 호출 실패, 폴백 적용: {}", e.getMessage());
            return fallbackTravelMinutes(fromLat, fromLng, toLat, toLng, mode);
        }
    }

    /**
     * 도보 / 대중교통 / 자동차 3가지 이동시간을 한 번에 반환
     * impact API에서 사용자가 이동수단을 직접 선택할 수 있도록 모두 계산
     * 각 모드별로 Distance Matrix 호출 → 실패 시 Haversine 폴백
     */
    public Map<TransportMode, Integer> getAllTravelTimeMinutes(
            double fromLat, double fromLng, double toLat, double toLng) {

        Map<TransportMode, Integer> result = new LinkedHashMap<>();
        for (TransportMode mode : List.of(TransportMode.WALK, TransportMode.TRANSIT, TransportMode.CAR)) {
            result.put(mode, getTravelTimeMinutes(fromLat, fromLng, toLat, toLng, mode));
        }
        return result;
    }

    /** Distance Matrix 실패 시 Haversine 직선거리 + 고정 속도로 폴백 */
    private int fallbackTravelMinutes(double fromLat, double fromLng,
                                      double toLat, double toLng,
                                      TransportMode mode) {
        double km    = haversineKm(fromLat, fromLng, toLat, toLng);
        double speed = (mode != null) ? mode.getKmPerMin() : TransportMode.WALK.getKmPerMin();
        return (int) Math.ceil(km / speed);
    }

    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** PLAN B Enum → 구글 Places API type 변환 (Google raw 카테고리 문자열도 처리) */
    private String mapToGoogleType(String category) {
        if (category == null) return "";
        return switch (category.toUpperCase()) {
            // PLAN B PlaceType enum
            case "FOOD"              -> "restaurant";
            case "CAFE"              -> "cafe";
            case "SIGHTS"            -> "tourist_attraction";
            case "SHOP"              -> "shopping_mall";
            case "MARKET"            -> "establishment";
            case "THEME"             -> "amusement_park";
            case "CULTURE"           -> "museum";
            case "PARK"              -> "park";
            // Google raw 카테고리 문자열 (AI 분석 미완료 시 place.category 폴백용)
            case "RESTAURANT", "MEAL_TAKEAWAY", "MEAL_DELIVERY", "FOOD_POINT_OF_INTEREST" -> "restaurant";
            case "BAKERY"            -> "cafe";
            case "TOURIST_ATTRACTION", "POINT_OF_INTEREST" -> "tourist_attraction";
            case "SHOPPING_MALL", "DEPARTMENT_STORE", "CLOTHING_STORE" -> "shopping_mall";
            case "AMUSEMENT_PARK"    -> "amusement_park";
            case "MUSEUM", "ART_GALLERY" -> "museum";
            case "NATURAL_FEATURE", "CAMPGROUND" -> "park";
            default                  -> "";
        };
    }
}
