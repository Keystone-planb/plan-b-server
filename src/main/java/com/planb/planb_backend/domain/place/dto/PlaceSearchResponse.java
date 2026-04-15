package com.planb.planb_backend.domain.place.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class PlaceSearchResponse {

    private List<PlaceItem> places;

    @Getter
    @Builder
    public static class PlaceItem {
        private String placeId;
        private String name;
        private String address;
        private double rating;
        private String category;
    }
}
