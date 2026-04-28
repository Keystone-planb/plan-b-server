package com.planb.planb_backend.domain.recommendation.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.place.entity.Place;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class PlaceResult {

    private Long placeId;
    private String googlePlaceId;
    private String name;
    private String category;
    private String space;
    private String type;
    private String mood;
    private Double rating;
    private Integer reviewCount;
    private Double latitude;
    private Double longitude;
    private String reviewSummary;   // AI 종합 한줄 요약

    private static final ObjectMapper mapper = new ObjectMapper();

    public static PlaceResult from(Place place) {
        return PlaceResult.builder()
                .placeId(place.getId())
                .googlePlaceId(place.getGooglePlaceId())
                .name(place.getName())
                .category(place.getCategory())
                .space(place.getSpace() != null ? place.getSpace().name() : null)
                .type(place.getType() != null ? place.getType().name() : null)
                .mood(place.getMood() != null ? place.getMood().name() : null)
                .rating(place.getRating())
                .reviewCount(place.getUserRatingsTotal())
                .latitude(place.getLatitude())
                .longitude(place.getLongitude())
                .reviewSummary(extractTotalSummary(place.getReviewData()))
                .build();
    }

    private static String extractTotalSummary(String reviewData) {
        if (reviewData == null || reviewData.isBlank()) return null;
        try {
            Map<?, ?> map = mapper.readValue(reviewData, Map.class);
            Object summary = map.get("totalSummary");
            return summary != null ? summary.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
