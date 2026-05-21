package com.planb.planb_backend.domain.place.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceSearchResponse {

    private List<PlaceItem> places;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlaceItem {
        private String placeId;
        private String name;
        private String address;
        private double rating;
        private String category;
    }
}
