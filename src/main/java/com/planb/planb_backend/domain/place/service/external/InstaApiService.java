package com.planb.planb_backend.domain.place.service.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

@Slf4j
@Service
public class InstaApiService {

    @Value("${rapidapi.key}")
    private String rapidApiKey;

    private static final String RAPID_HOST = "instagram-scraper-20251.p.rapidapi.com";

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://" + RAPID_HOST)
            .build();

    /**
     * 장소명과 좌표를 이용해 인스타그램 포스트 본문(Caption) 수집
     */
    public List<String> getInstagramReviews(String placeName, Double lat, Double lng) {
        log.info(">>>> [Insta API Request] 장소명: {}, 위치: {}, {}", placeName, lat, lng);

        try {
            String locationId = fetchLocationId(placeName, lat, lng);
            if (locationId == null) {
                log.warn(">>>> [Insta API] 적절한 Location ID를 찾을 수 없음: {}", placeName);
                return Collections.emptyList();
            }

            Map<String, Object> feedResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/locationposts/")
                            .queryParam("location_id", locationId)
                            .build())
                    .header("X-RapidAPI-Key", rapidApiKey)
                    .header("X-RapidAPI-Host", RAPID_HOST)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseCaptions(feedResponse);

        } catch (Exception e) {
            log.error(">>>> [Insta API Error] 전역 예외: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String fetchLocationId(String placeName, Double lat, Double lng) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/searchcoordinates/")
                            .queryParam("latitude", lat)
                            .queryParam("longitude", lng)
                            .build())
                    .header("X-RapidAPI-Key", rapidApiKey)
                    .header("X-RapidAPI-Host", RAPID_HOST)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.get("data") instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                if (data.get("items") instanceof List) {
                    List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
                    String targetName = placeName.replaceAll("\\s", "").toLowerCase();

                    for (Map<String, Object> item : items) {
                        String instaName = String.valueOf(item.get("name")).replaceAll("\\s", "").toLowerCase();
                        if (instaName.contains(targetName) || targetName.contains(instaName)) {
                            log.info(">>>> [Insta API] 장소 일치: {} (ID: {})", item.get("name"), item.get("id"));
                            return String.valueOf(item.get("id"));
                        }
                    }
                    if (!items.isEmpty()) {
                        log.warn(">>>> [Insta API] 이름 불일치로 1순위 선택: {}", items.get(0).get("name"));
                        return String.valueOf(items.get(0).get("id"));
                    }
                }
            }
        } catch (Exception e) {
            log.error(">>>> [Insta ID Search Error]: {}", e.getMessage());
        }
        return null;
    }

    private List<String> parseCaptions(Map<String, Object> response) {
        List<String> captions = new ArrayList<>();
        if (response == null || !(response.get("data") instanceof Map)) return captions;

        try {
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data.get("items") instanceof List) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
                for (Map<String, Object> item : items) {
                    if (item.get("caption") instanceof Map) {
                        Map<String, Object> caption = (Map<String, Object>) item.get("caption");
                        String text = String.valueOf(caption.get("text"));
                        if (text != null && !text.isEmpty() && !text.equals("null")) {
                            captions.add(text);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn(">>>> [Insta Parsing Error]: {}", e.getMessage());
        }
        return captions;
    }
}
