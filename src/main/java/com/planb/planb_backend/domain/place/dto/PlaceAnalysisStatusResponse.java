package com.planb.planb_backend.domain.place.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PlaceAnalysisStatusResponse {

    private String placeId;
    private String status;   // "PENDING" | "COMPLETE"
}
