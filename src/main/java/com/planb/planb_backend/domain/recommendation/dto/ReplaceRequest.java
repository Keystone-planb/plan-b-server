package com.planb.planb_backend.domain.recommendation.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ReplaceRequest {

    /** 새로 대체할 Google Place ID */
    private String newGooglePlaceId;

    /** 새 장소 이름 */
    private String newPlaceName;
}
