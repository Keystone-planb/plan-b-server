package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.Trip;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
public class TripSummaryResponse {

    private final Long tripId;
    private final String title;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final List<Mood> travelStyles;

    private TripSummaryResponse(Trip trip) {
        this.tripId      = trip.getTripId();
        this.title       = trip.getTitle();
        this.startDate   = trip.getStartDate();
        this.endDate     = trip.getEndDate();
        this.travelStyles = trip.getTravelStyles();
    }

    public static TripSummaryResponse from(Trip trip) {
        return new TripSummaryResponse(trip);
    }
}
