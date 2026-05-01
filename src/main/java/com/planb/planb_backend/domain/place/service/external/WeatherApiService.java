package com.planb.planb_backend.domain.place.service.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * OpenWeatherMap Forecast API 연동
 * - 3시간 단위 예보에서 특정 시각의 강수 확률(POP) 추출
 * - API 키 미설정 시 -1 반환 (스케줄러에서 Skip 처리)
 */
@Slf4j
@Service
public class WeatherApiService {

    @Value("${weather.api-key:}")
    private String apiKey;

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.openweathermap.org/data/2.5")
            .build();

    /**
     * 특정 좌표 + 시각의 강수 확률 조회
     *
     * @param lat        위도
     * @param lon        경도
     * @param targetTime 확인할 예보 시각
     * @return 강수 확률 0~100, API 키 미설정 또는 조회 실패 시 -1
     */
    public int getPrecipitationProbability(double lat, double lon, LocalDateTime targetTime) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[WeatherAPI] API 키 미설정 — 강수 확률 조회 스킵");
            return -1;
        }

        try {
            Map<?, ?> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/forecast")
                            .queryParam("lat", lat)
                            .queryParam("lon", lon)
                            .queryParam("appid", apiKey)
                            .queryParam("cnt", 8)        // 24시간치 (3h × 8)
                            .queryParam("units", "metric")
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !response.containsKey("list")) return -1;

            List<Map<String, Object>> forecasts = (List<Map<String, Object>>) response.get("list");

            // 대상 시각과 가장 가까운 예보 슬롯 찾기
            return forecasts.stream()
                    .min((a, b) -> {
                        long diffA = Math.abs(parseEpoch(a) - toEpoch(targetTime));
                        long diffB = Math.abs(parseEpoch(b) - toEpoch(targetTime));
                        return Long.compare(diffA, diffB);
                    })
                    .map(slot -> {
                        Object pop = slot.get("pop");
                        if (pop == null) return 0;
                        return (int) Math.round(((Number) pop).doubleValue() * 100);
                    })
                    .orElse(0);

        } catch (Exception e) {
            log.warn("[WeatherAPI] 조회 실패 (lat={}, lon={}): {}", lat, lon, e.getMessage());
            return -1;
        }
    }

    private long parseEpoch(Map<String, Object> slot) {
        Object dt = slot.get("dt");
        return dt != null ? ((Number) dt).longValue() : 0L;
    }

    private long toEpoch(LocalDateTime dt) {
        return dt.toEpochSecond(java.time.ZoneOffset.of("+09:00"));
    }
}
