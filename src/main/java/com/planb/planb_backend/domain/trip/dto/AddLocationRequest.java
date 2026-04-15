package com.planb.planb_backend.domain.trip.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class AddLocationRequest {

    @NotBlank(message = "place_id는 필수입니다.")
    private String placeId;

    @NotBlank(message = "장소 이름은 필수입니다.")
    private String name;

    private String visitTime;  // "HH:mm" 형식 (선택)

    private String memo;       // 사용자 메모 (선택)
}
