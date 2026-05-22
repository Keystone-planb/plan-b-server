package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.PlaceSource;
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
    private String endTime;
    private int visitOrder;
    private String memo;
    private PlaceSource source;  // 추가 출처 (NORMAL / SOS / WEATHER / GAP)

    public static AddLocationResponse from(TripPlace tripPlace) {
        return AddLocationResponse.builder()
                .tripPlaceId(tripPlace.getTripPlaceId())
                .placeId(tripPlace.getPlaceId())
                .name(tripPlace.getName())
                .visitTime(tripPlace.getVisitTime())
                .endTime(tripPlace.getEndTime())
                .visitOrder(tripPlace.getVisitOrder())
                .memo(tripPlace.getMemo())
                .source(tripPlace.getSource())
                .build();
    }
}
