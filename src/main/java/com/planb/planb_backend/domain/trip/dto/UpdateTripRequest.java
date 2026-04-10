package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.Mood;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

// PATCH: 보내는 필드만 수정 (null이면 유지)
@Getter
public class UpdateTripRequest {

    private String title;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<Mood> travelStyles;
}
