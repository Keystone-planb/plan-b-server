package com.planb.planb_backend.domain.place.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PlaceSummaryResponse {

    private String  placeId;
    private boolean analyzed;    // 분석 시도 완료 여부 (true = 분석 끝남, aiSummary null이면 리뷰 없음)
    private String  aiSummary;    // 전체 한줄 요약 (미분석 시 null)
    private String  googleReview; // 구글 플랫폼 요약 (미분석 시 null)
    private String  naverReview;  // 네이버 블로그 플랫폼 요약 (미분석 시 null)
    private String  instaReview;  // 인스타그램 플랫폼 요약 (미분석 시 null)
}
