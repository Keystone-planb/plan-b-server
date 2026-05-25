package com.planb.planb_backend.domain.trip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateMemoRequest {

    @NotBlank(message = "메모 내용은 필수입니다.")
    @Schema(description = "수정할 메모 내용", example = "입장권 미리 예매하기 (수정)")
    private String content;
}
