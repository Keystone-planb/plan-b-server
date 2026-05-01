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
    private Double rating;
    private String address;
    private String photoUrl;

    public static AlternativePlaceDto from(Place place) {
        return AlternativePlaceDto.builder()
                .placeId(place.getId())
                .name(place.getName())
                .category(place.getCategory())
                .rating(place.getRating())
                .address(place.getAddress())
                .photoUrl(place.getPhotoUrl())
                .build();
    }
}
