package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.Itinerary;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
public class TripDetailResponse {

    private final Long tripId;
    private final String title;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final List<Mood> travelStyles;
    private final List<ItineraryResponse> itineraries;

    private TripDetailResponse(Trip trip) {
        this.tripId       = trip.getTripId();
        this.title        = trip.getTitle();
        this.startDate    = trip.getStartDate();
        this.endDate      = trip.getEndDate();
        this.travelStyles = trip.getTravelStyles();
        this.itineraries  = trip.getItineraries().stream()
                .map(ItineraryResponse::from)
                .toList();
    }

    public static TripDetailResponse from(Trip trip) {
        return new TripDetailResponse(trip);
    }

    // ── 일정(Itinerary) 응답 ───────────────────────────────────────
    @Getter
    public static class ItineraryResponse {
        private final Long itineraryId;
        private final int day;
        private final LocalDate date;
        private final List<PlaceResponse> places;

        private ItineraryResponse(Itinerary itinerary) {
            this.itineraryId = itinerary.getItineraryId();
            this.day         = itinerary.getDay();
            this.date        = itinerary.getDate();
            this.places      = itinerary.getPlaces().stream()
                    .map(PlaceResponse::from)
                    .toList();
        }

        public static ItineraryResponse from(Itinerary itinerary) {
            return new ItineraryResponse(itinerary);
        }
    }

    // ── 방문지(TripPlace) 응답 ─────────────────────────────────────
    @Getter
    public static class PlaceResponse {
        private final Long tripPlaceId;
        private final String placeId;
        private final String name;
        private final String visitTime;
        private final int visitOrder;

        private PlaceResponse(TripPlace place) {
            this.tripPlaceId = place.getTripPlaceId();
            this.placeId     = place.getPlaceId();
            this.name        = place.getName();
            this.visitTime   = place.getVisitTime();
            this.visitOrder  = place.getVisitOrder();
        }

        public static PlaceResponse from(TripPlace place) {
            return new PlaceResponse(place);
        }
    }
}
