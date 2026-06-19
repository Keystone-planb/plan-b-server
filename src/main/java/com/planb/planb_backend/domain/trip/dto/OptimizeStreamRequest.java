package com.planb.planb_backend.domain.trip.dto;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

// 신규 추가: 중복 방지 로직
@Getter
public class OptimizeStreamRequest {

    /**
     * 이전 조회에서 이미 추천된 장소들의 Google Place ID 목록.
     * 재조회 시 이 목록에 있는 장소는 AI 서버 검색에서 제외됩니다.
     *
     * - null이거나 비어있으면 기존 로직 100% 동일하게 동작 (하위 호환)
     * - 프론트가 준비되기 전까지는 이 필드를 보내지 않아도 됩니다.
     */
    private List<String> excludedPlaceIds;

    public List<String> getExcludedPlaceIds() {
        return (excludedPlaceIds != null) ? excludedPlaceIds : Collections.emptyList();
    }
}
