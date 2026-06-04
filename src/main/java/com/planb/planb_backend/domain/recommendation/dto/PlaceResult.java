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

    /**
     * [기능 6 — 틈새 추천] 이 장소를 일정에 추가할 때 사용할 제안 시각 ("HH:mm")
     * null 이면 프론트가 직접 시간 입력 (SOS / 날씨 대안)
     * 값이 있으면 프론트가 addLocation 호출 시 visitTime/endTime 으로 그대로 전달
     */
    private String suggestedVisitTime;
    private String suggestedEndTime;

    private static final ObjectMapper mapper = new ObjectMapper();

    /** SOS / 날씨 대안용: 제안 시각 없음 */
    public static PlaceResult from(Place place) {
        return from(place, null, null);
    }

    /** 틈새 추천용: 제안 시각 포함 */
    public static PlaceResult from(Place place, String suggestedVisitTime, String suggestedEndTime) {
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
                .suggestedVisitTime(suggestedVisitTime)
                .suggestedEndTime(suggestedEndTime)
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
