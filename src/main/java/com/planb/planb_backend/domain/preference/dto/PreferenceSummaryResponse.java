package com.planb.planb_backend.domain.preference.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PreferenceSummaryResponse {

    private Long    userId;
    private boolean hasEnoughData;
    private String  message;        // 데이터 부족 시 null
}
