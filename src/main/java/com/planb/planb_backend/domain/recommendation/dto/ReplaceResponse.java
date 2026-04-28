package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ReplaceResponse {

    private Long tripPlaceId;
    private String googlePlaceId;
    private String name;
    private String message;
}
