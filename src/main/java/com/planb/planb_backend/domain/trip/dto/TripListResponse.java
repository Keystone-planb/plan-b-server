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

    /**
     * @param placeCount 호출부(TripService.getMyTrips)에서 itinerary별 장소 개수를
     *                    GROUP BY 쿼리 1회로 미리 집계해서 전달 — itinerary.getPlaces()를
     *                    여기서 직접 건드리면 이티너리마다 lazy 쿼리가 하나씩 나가는 N+1이 됨
     */
    public static TripListResponse from(Trip trip, int placeCount) {
        int itineraryCount = trip.getItineraries().size();

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
