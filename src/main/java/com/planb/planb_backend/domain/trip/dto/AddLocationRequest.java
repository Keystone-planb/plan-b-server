package com.planb.planb_backend.domain.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.planb.planb_backend.domain.trip.entity.PlaceSource;
import com.planb.planb_backend.domain.trip.entity.TransportMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class AddLocationRequest {

    // JSON 요청은 snake_case("place_id"), Java 필드는 camelCase(placeId) — 양쪽 모두 허용
    @JsonProperty("place_id")
    @NotBlank(message = "place_id는 필수입니다.")
    private String placeId;

    @NotBlank(message = "장소 이름은 필수입니다.")
    private String name;

    private String visitTime;  // 시작 시간 "HH:mm" (선택)

    private String endTime;    // 종료 시간 "HH:mm" (선택)

    private String memo;       // 사용자 메모 (선택)

    private PlaceSource source; // 추가 출처 (NORMAL / SOS / WEATHER / GAP), 미전송 시 null → NORMAL로 처리

    /** 이 장소 → 다음 장소 이동 수단. 미전송 시 null (Trip 기본값 폴백) */
    private TransportMode transportMode;
}
