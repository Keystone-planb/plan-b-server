package com.planb.planb_backend.domain.place.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class PlaceDetailResponse {

    private String placeId;
    private String name;
    private String address;
    private Double rating;          // 없을 수 있으므로 Double (null 허용)
    private String openingHours;    // 요일별 영업시간 텍스트 (" / "로 구분)
    private double lat;
    private double lng;
    private List<Review> reviews;   // AI(LLM) 분석에 전달할 리뷰 목록

    // AI 분석 태그 (DB places 테이블에서 조회, 미분석 장소는 null)
    private String space;           // INDOOR / OUTDOOR / MIX
    private String type;            // FOOD / CAFE / SIGHTS / SHOP / MARKET / THEME / CULTURE / PARK
    private String mood;            // 분위기 태그

    /**
     * 구글 Places API reviews 배열의 단일 리뷰
     * AI 분석용으로 텍스트 데이터를 원문 그대로 보존합니다.
     */
    @Getter
    @Builder
    public static class Review {
        private String text;                    // 리뷰 본문 (AI 분석 대상)
        private int rating;                     // 별점 1~5
        private String relativeTimeDescription; // 작성 시기 (예: "3개월 전")
    }
}
