package com.planb.planb_backend.domain.preference.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class FeedbackRequest {

    private Long       userId;
    private List<Long> shownPlaceIds;    // 추천 결과로 노출된 장소 DB PK 목록
    private Long       selectedPlaceId;  // 사용자가 실제 선택한 장소 DB PK
}
