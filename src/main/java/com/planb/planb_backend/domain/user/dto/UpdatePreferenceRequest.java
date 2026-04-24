package com.planb.planb_backend.domain.user.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class UpdatePreferenceRequest {

    private String travelStyle;
    private List<String> preferences;
}
