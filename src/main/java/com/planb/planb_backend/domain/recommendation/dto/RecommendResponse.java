package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class RecommendResponse {

    private List<PlaceResult> recommendations;
    private int totalCount;
}
