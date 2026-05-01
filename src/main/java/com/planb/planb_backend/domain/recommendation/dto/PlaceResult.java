package com.planb.planb_backend.domain.recommendation.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.place.entity.Place;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
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
    private String address;             // 한국어 주소
    private String reviewSummary;       // AI 종합 한줄 요약
    private String googleReview;        // 구글 리뷰 플랫폼 요약
    private String naverReview;         // 네이버 블로그 플랫폼 요약
    private String instaReview;         // 인스타그램 플랫폼 요약

    // 영업 정보
    private String businessStatus;      // OPERATIONAL / CLOSED_TEMPORARILY / CLOSED_PERMANENTLY
    private String openingHours;        // JSON 문자열
    private String phoneNumber;
    private String website;
    private Integer priceLevel;         // 0~4

    private String photoUrl;
    private LocalDateTime lastSyncedAt;

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
                .address(place.getAddress())
                .reviewSummary(extractTotalSummary(place.getReviewData()))
                .googleReview(extractPlatformSummary(place.getReviewData(), "Google"))
                .naverReview(extractPlatformSummary(place.getReviewData(), "Naver"))
                .instaReview(extractPlatformSummary(place.getReviewData(), "Instagram"))
                .businessStatus(place.getBusinessStatus() != null ? place.getBusinessStatus().name() : null)
                .openingHours(place.getOpeningHours())
                .phoneNumber(place.getPhoneNumber())
                .website(place.getWebsite())
                .priceLevel(place.getPriceLevel())
                .photoUrl(place.getPhotoUrl())
                .lastSyncedAt(place.getLastSyncedAt())
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

    private static String extractPlatformSummary(String reviewData, String platform) {
        if (reviewData == null || reviewData.isBlank()) return null;
        try {
            Map<?, ?> map = mapper.readValue(reviewData, Map.class);
            Object summaries = map.get("platformSummaries");
            if (!(summaries instanceof Map)) return null;
            Object value = ((Map<?, ?>) summaries).get(platform);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
