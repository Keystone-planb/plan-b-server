package com.planb.planb_backend.domain.place.service.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.HtmlUtils;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NaverApiService {

    @Value("${naver.client.id:}")
    private String clientId;

    @Value("${naver.client.secret:}")
    private String clientSecret;

    private static final ConnectionProvider NAVER_POOL = ConnectionProvider.builder("naver-pool")
            .maxConnections(10)
            .maxIdleTime(Duration.ofSeconds(10))
            .evictInBackground(Duration.ofSeconds(30))
            .build();

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://openapi.naver.com")
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create(NAVER_POOL)
                            .responseTimeout(Duration.ofSeconds(10))))
            .build();

    /**
     * 네이버 블로그 검색 API로 장소 후기 수집
     */
    public List<String> getNaverReviews(String placeName) {
        log.info(">>>> [Naver API Request] 장소명: {}", placeName);

        try {
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/search/blog.json")
                            .queryParam("query", placeName + " 후기")
                            .queryParam("display", 10)
                            .queryParam("sort", "sim")
                            .build())
                    .header("X-Naver-Client-Id", clientId)
                    .header("X-Naver-Client-Secret", clientSecret)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(10)); // 10초 초과 시 IllegalStateException → catch(Exception)으로 빈 리스트 반환

            if (response == null || !response.containsKey("items")) {
                log.error(">>>> [Naver API Error] 응답 형식이 올바르지 않거나 items가 없음");
                return Collections.emptyList();
            }

            Object itemsObj = response.get("items");
            if (!(itemsObj instanceof List)) return Collections.emptyList();

            List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
            log.info(">>>> [Naver API Success] 수집된 아이템 개수: {}개", items.size());

            return items.stream()
                    .map(item -> {
                        String title = Objects.toString(item.get("title"), "");
                        String description = Objects.toString(item.get("description"), "");
                        String noTag = (title + " " + description).replaceAll("<[^>]*>", "");
                        return HtmlUtils.htmlUnescape(noTag);
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error(">>>> [Naver API Exception] 에러 메시지: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
