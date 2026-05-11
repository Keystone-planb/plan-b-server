package com.planb.planb_backend.domain.place.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PlaceSummaryResponse {

    private String placeId;
    private String aiSummary;    // 전체 한줄 요약 (미분석 시 null)
    private String googleReview; // 구글 플랫폼 요약 (미분석 시 null)
    private String naverReview;  // 네이버 블로그 플랫폼 요약 (미분석 시 null)
    private String instaReview;  // 인스타그램 플랫폼 요약 (미분석 시 null)
}
