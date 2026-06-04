package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.Trip;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class TripListResponse {

    private Long tripId;
    private String title;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;         // UPCOMING / ONGOING / PAST
    private int itineraryCount;    // 전체 일차 수
    private int placeCount;        // 전체 장소 수

    public static TripListResponse from(Trip trip) {
        int itineraryCount = trip.getItineraries().size();
        int placeCount = trip.getItineraries().stream()
                .mapToInt(it -> it.getPlaces().size())
                .sum();

        return TripListResponse.builder()
                .tripId(trip.getTripId())
                .title(trip.getTitle())
                .startDate(trip.getStartDate())
                .endDate(trip.getEndDate())
                .status(computeStatus(trip.getStartDate(), trip.getEndDate()))
                .itineraryCount(itineraryCount)
                .placeCount(placeCount)
                .build();
    }

    private static String computeStatus(LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now();
        if (today.isBefore(startDate)) return "UPCOMING";
        if (today.isAfter(endDate))    return "PAST";
        return "ONGOING";
    }
}
