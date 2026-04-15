package com.planb.planb_backend.domain.place.service;

import com.planb.planb_backend.domain.place.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Google Places API + OpenAI API 연동 서비스
 * 현재는 Mock 데이터를 반환합니다. (실제 API 키 설정 후 구현 예정)
 */
@Slf4j
@Service
public class PlaceService {

    // 실제 키 미설정 시 서버 시작 실패를 막기 위해 빈 문자열 기본값 사용
    // 실제 API 연동 시 application-local.yml 또는 ECS 환경변수에 키 입력 필요
    @Value("${google.places-api-key:}")
    private String googlePlacesApiKey;

    @Value("${openai.api-key:}")
    private String openaiApiKey;

    /**
     * GET /api/places/search?query=&lat=&lng=
     * 장소 검색 (Google Places Text Search API 예정)
     */
    public PlaceSearchResponse searchPlaces(String query, double lat, double lng) {
        log.info("[Place] 장소 검색 요청 - query: {}, lat: {}, lng: {}", query, lat, lng);

        // TODO: Google Places API 연동
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
     * 장소 상세 정보 조회 (Google Places Details API 예정)
     */
    public PlaceDetailResponse getPlaceDetail(String placeId) {
        log.info("[Place] 장소 상세 조회 - placeId: {}", placeId);

        // TODO: Google Places Details API 연동
        return PlaceDetailResponse.builder()
                .placeId(placeId)
                .name("성수동 카페 A")
                .address("서울특별시 성동구 성수이로 20")
                .rating(4.6)
                .phoneNumber("02-1234-5678")
                .openingHours("매일 09:00 - 22:00")
                .photos(List.of("https://example.com/photo1.jpg"))
                .tags(PlaceDetailResponse.Tags.builder()
                        .space("INDOOR")
                        .type("CAFE")
                        .mood("HEALING")
                        .build())
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
