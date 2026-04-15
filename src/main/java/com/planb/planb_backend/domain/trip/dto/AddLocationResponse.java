package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.TripPlace;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AddLocationResponse {

    private Long tripPlaceId;
    private String placeId;
    private String name;
    private String visitTime;
    private int visitOrder;
    private String memo;

    public static AddLocationResponse from(TripPlace tripPlace) {
        return AddLocationResponse.builder()
                .tripPlaceId(tripPlace.getTripPlaceId())
                .placeId(tripPlace.getPlaceId())
                .name(tripPlace.getName())
                .visitTime(tripPlace.getVisitTime())
                .visitOrder(tripPlace.getVisitOrder())
                .memo(tripPlace.getMemo())
                .build();
    }
}
