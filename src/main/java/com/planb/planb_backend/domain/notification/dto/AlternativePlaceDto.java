package com.planb.planb_backend.domain.notification.dto;

import com.planb.planb_backend.domain.place.entity.Place;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AlternativePlaceDto {

    private Long   placeId;
    private String name;
    private String category;
    private String space;            // INDOOR / OUTDOOR / MIX
    private Double rating;
    private Integer userRatingsTotal; // 리뷰 수
    private String address;
    private String photoUrl;
    private String phoneNumber;
    private String website;
    private Integer priceLevel;      // 0(무료) ~ 4(매우 비쌈), null이면 정보 없음
    private String openingHours;     // JSONB 문자열 {"weekday_text": [...]}
    private String reviewData;       // JSONB 문자열 — 구글 리뷰 원본

    public static AlternativePlaceDto from(Place place) {
        return AlternativePlaceDto.builder()
                .placeId(place.getId())
                .name(place.getName())
                .category(place.getCategory())
                .space(place.getSpace() != null ? place.getSpace().name() : null)
                .rating(place.getRating())
                .userRatingsTotal(place.getUserRatingsTotal())
                .address(place.getAddress())
                .photoUrl(place.getPhotoUrl())
                .phoneNumber(place.getPhoneNumber())
                .website(place.getWebsite())
                .priceLevel(place.getPriceLevel())
                .openingHours(place.getOpeningHours())
                .reviewData(place.getReviewData())
                .build();
    }
}
