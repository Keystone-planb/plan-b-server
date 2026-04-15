package com.planb.planb_backend.domain.place.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PlaceFreshnessResponse {

    private String placeId;
    private String lastUpdated;      // "2026-04-10" 형식
    private String status;           // FRESH / STALE / UNKNOWN
    private double confidenceScore;  // 0.0 ~ 1.0
}
