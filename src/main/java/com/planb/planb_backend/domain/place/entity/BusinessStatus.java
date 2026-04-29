package com.planb.planb_backend.domain.place.entity;

/**
 * 구글 Places API의 business_status 값과 1:1 매핑
 * https://developers.google.com/maps/documentation/places/web-service/details#PlaceOpeningHours
 */
public enum BusinessStatus {
    OPERATIONAL,            // 정상 영업 중
    CLOSED_TEMPORARILY,     // 임시 휴업
    CLOSED_PERMANENTLY      // 폐업
}
