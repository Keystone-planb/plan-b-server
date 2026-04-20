package com.planb.planb_backend.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 구글 Places API 응답을 프론트엔드와 AI 분석에 맞게 정제한 DTO
 */
@Getter
@Builder
public class PlaceSearchResponseDto {

    private String placeId;      // 구글 장소 고유 ID
    private String placeName;    // 장소 이름
    private String address;      // 도로명 주소
    private double lat;          // 위도
    private double lng;          // 경도
    private Double rating;       // 평점 (없을 수 있어 Double 사용)
    private List<String> types;  // 장소 유형 (예: restaurant, cafe 등)
}
