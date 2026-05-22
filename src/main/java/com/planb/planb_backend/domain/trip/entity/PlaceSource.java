package com.planb.planb_backend.domain.trip.entity;

/**
 * 일정에 장소가 추가된 출처.
 * TripPlace.source 에 저장되어 프론트엔드의 색상 구별에 사용된다.
 */
public enum PlaceSource {
    NORMAL,   // 직접 검색하여 추가
    SOS,      // SOS 대안 추천에서 추가
    WEATHER,  // 날씨 대안 추천에서 추가
    GAP       // 틈새 추천에서 추가
}
