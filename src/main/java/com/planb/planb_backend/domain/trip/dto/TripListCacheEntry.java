package com.planb.planb_backend.domain.trip.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * TripService.getMyTrips()의 Redis 캐싱 전용 래퍼.
 * List<TripListResponse>를 @Cacheable 반환값으로 그대로 캐싱하면 Jackson 기본 타이핑이
 * 최상위 제네릭 컬렉션의 타입 정보를 온전히 못 붙여서 역직렬화 시
 * "Unexpected token (START_ARRAY), expected VALUE_STRING" 오류로 매번 캐시 조회가 실패함.
 * POJO로 한 번 감싸면 필드의 정적 타입 정보를 Jackson이 활용할 수 있어 정상 동작함.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripListCacheEntry {
    private List<TripListResponse> trips;
}
