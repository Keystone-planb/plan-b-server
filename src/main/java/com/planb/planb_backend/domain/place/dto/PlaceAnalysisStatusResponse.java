package com.planb.planb_backend.domain.place.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceAnalysisStatusResponse {

    private String placeId;
    private String status;   // "PENDING" | "COMPLETE"
}
