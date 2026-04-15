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
    private double rating;
    private String phoneNumber;
    private String openingHours;
    private List<String> photos;
    private Tags tags;

    @Getter
    @Builder
    public static class Tags {
        private String space;   // INDOOR / OUTDOOR / MIX
        private String type;    // FOOD / CAFE / SIGHTS 등
        private String mood;    // HEALING / ACTIVE 등
    }
}
