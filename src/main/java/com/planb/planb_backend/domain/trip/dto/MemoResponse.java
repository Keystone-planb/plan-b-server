package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.TripPlaceMemo;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class MemoResponse {

    private Long id;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static MemoResponse from(TripPlaceMemo memo) {
        return MemoResponse.builder()
                .id(memo.getId())
                .content(memo.getContent())
                .createdAt(memo.getCreatedAt())
                .updatedAt(memo.getUpdatedAt())
                .build();
    }
}
