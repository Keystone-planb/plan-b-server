package com.planb.planb_backend.domain.place.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class PlaceSummaryResponse {

    private String placeId;
    private String aiSummary;
    private List<String> keywords;
}
