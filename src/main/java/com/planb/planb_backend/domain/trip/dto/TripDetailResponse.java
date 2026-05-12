package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.Itinerary;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Getter
public class TripDetailResponse {

    private final Long tripId;
    private final String title;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final List<Mood> travelStyles;
    private final List<ItineraryResponse> itineraries;

    private TripDetailResponse(Trip trip, Map<String, double[]> coordMap) {
        this.tripId       = trip.getTripId();
        this.title        = trip.getTitle();
        this.startDate    = trip.getStartDate();
        this.endDate      = trip.getEndDate();
        this.travelStyles = trip.getTravelStyles();
        this.itineraries  = trip.getItineraries().stream()
                .map(i -> ItineraryResponse.from(i, coordMap))
                .toList();
    }

    public static TripDetailResponse from(Trip trip, Map<String, double[]> coordMap) {
        return new TripDetailResponse(trip, coordMap);
    }

    // ── 일정(Itinerary) 응답 ───────────────────────────────────────
    @Getter
    public static class ItineraryResponse {
        private final Long itineraryId;
        private final int day;
        private final LocalDate date;
        private final List<PlaceResponse> places;

        private ItineraryResponse(Itinerary itinerary, Map<String, double[]> coordMap) {
            this.itineraryId = itinerary.getItineraryId();
            this.day         = itinerary.getDay();
            this.date        = itinerary.getDate();
            this.places      = buildPlaceResponses(itinerary, coordMap);
        }

        public static ItineraryResponse from(Itinerary itinerary, Map<String, double[]> coordMap) {
            return new ItineraryResponse(itinerary, coordMap);
        }

        /**
         * visitTime 기준 오름차순 정렬 후,
         * 이전 장소 endTime ↔ 다음 장소 visitTime 사이의 여유 시간(분)을 계산해서 주입
         */
        private static List<PlaceResponse> buildPlaceResponses(Itinerary itinerary, Map<String, double[]> coordMap) {
            // visitTime null이면 맨 뒤로 정렬
            List<TripPlace> sorted = new ArrayList<>(itinerary.getPlaces());
            sorted.sort(Comparator.comparing(
                tp -> tp.getVisitTime() != null ? tp.getVisitTime() : "99:99"
            ));

            List<PlaceResponse> result = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                TripPlace current = sorted.get(i);
                Integer gap = null;

                if (i < sorted.size() - 1) {
                    TripPlace next = sorted.get(i + 1);
                    if (current.getEndTime() != null && next.getVisitTime() != null) {
                        try {
                            LocalTime endT   = LocalTime.parse(current.getEndTime());
                            LocalTime startT = LocalTime.parse(next.getVisitTime());
                            long minutes = ChronoUnit.MINUTES.between(endT, startT);
                            gap = minutes >= 0 ? (int) minutes : null;
                        } catch (Exception ignored) {
                            // 시간 파싱 실패 시 gap null 유지
                        }
                    }
                }

                double[] coords = coordMap.getOrDefault(current.getPlaceId(), null);
                Double lat = coords != null ? coords[0] : null;
                Double lng = coords != null ? coords[1] : null;
                result.add(PlaceResponse.from(current, gap, lat, lng));
            }
            return result;
        }
    }

    // ── 방문지(TripPlace) 응답 ─────────────────────────────────────
    @Getter
    public static class PlaceResponse {
        private final Long tripPlaceId;
        private final String placeId;
        private final String name;
        private final String visitTime;
        private final String endTime;
        private final int visitOrder;
        private final String memo;
        /** 다음 장소까지의 여유 시간(분). 마지막 장소는 null */
        private final Integer transitGapMinutes;
        /** DB places 테이블 기준 좌표 — 미분석 장소는 null */
        private final Double lat;
        private final Double lng;

        private PlaceResponse(TripPlace place, Integer transitGapMinutes, Double lat, Double lng) {
            this.tripPlaceId        = place.getTripPlaceId();
            this.placeId            = place.getPlaceId();
            this.name               = place.getName();
            this.visitTime          = place.getVisitTime();
            this.endTime            = place.getEndTime();
            this.visitOrder         = place.getVisitOrder();
            this.memo               = place.getMemo();
            this.transitGapMinutes  = transitGapMinutes;
            this.lat                = lat;
            this.lng                = lng;
        }

        public static PlaceResponse from(TripPlace place, Integer transitGapMinutes, Double lat, Double lng) {
            return new PlaceResponse(place, transitGapMinutes, lat, lng);
        }
    }
}
