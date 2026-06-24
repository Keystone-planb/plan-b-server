package com.planb.planb_backend.domain.trip.dto;

import com.planb.planb_backend.domain.trip.entity.Itinerary;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.PlaceSource;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.entity.TransportMode;
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
import java.util.stream.Collectors;

@Getter
public class TripDetailResponse {

    private final Long tripId;
    private final String title;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final List<Mood> travelStyles;
    private final List<ItineraryResponse> itineraries;

    /** DB places 테이블에서 배치 조회한 장소 메타데이터 (좌표 + 카테고리 + AI 분석 타입 + 실내외 구분) */
    public record PlaceInfo(Double latitude, Double longitude, String category, PlaceType type, String space) {}

    private TripDetailResponse(Trip trip, Map<String, PlaceInfo> placeInfoMap) {
        this.tripId       = trip.getTripId();
        this.title        = trip.getTitle();
        this.startDate    = trip.getStartDate();
        this.endDate      = trip.getEndDate();
        this.travelStyles = trip.getTravelStyles();
        this.itineraries  = trip.getItineraries().stream()
                .map(i -> ItineraryResponse.from(i, placeInfoMap))
                .toList();
    }

    public static TripDetailResponse from(Trip trip, Map<String, PlaceInfo> placeInfoMap) {
        return new TripDetailResponse(trip, placeInfoMap);
    }

    // ── 일정(Itinerary) 응답 ───────────────────────────────────────
    @Getter
    public static class ItineraryResponse {
        private final Long itineraryId;
        private final int day;
        private final LocalDate date;
        private final List<PlaceResponse> places;

        private ItineraryResponse(Itinerary itinerary, Map<String, PlaceInfo> placeInfoMap) {
            this.itineraryId = itinerary.getItineraryId();
            this.day         = itinerary.getDay();
            this.date        = itinerary.getDate();
            this.places      = buildPlaceResponses(itinerary, placeInfoMap);
        }

        public static ItineraryResponse from(Itinerary itinerary, Map<String, PlaceInfo> placeInfoMap) {
            return new ItineraryResponse(itinerary, placeInfoMap);
        }

        /**
         * visitTime 기준 오름차순 정렬 후,
         * 이전 장소 endTime ↔ 다음 장소 visitTime 사이의 여유 시간(분)을 계산해서 주입
         */
        private static List<PlaceResponse> buildPlaceResponses(Itinerary itinerary, Map<String, PlaceInfo> placeInfoMap) {
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

                PlaceInfo info = placeInfoMap.getOrDefault(current.getPlaceId(), null);
                Double lat      = info != null ? info.latitude()  : null;
                Double lng      = info != null ? info.longitude() : null;
                String category = info != null ? info.category()  : null;
                PlaceType type  = info != null ? info.type()      : null;
                String space    = info != null ? info.space()     : null;
                result.add(PlaceResponse.from(current, gap, lat, lng, category, type, space));
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
        /** 추가 출처 (NORMAL / SOS / WEATHER / GAP). 기존 데이터는 null */
        private final PlaceSource source;
        /** 이 장소 → 다음 장소 이동 수단. null 이면 Trip 기본값 사용 */
        private final TransportMode transportMode;
        /** 다음 장소까지의 여유 시간(분). 마지막 장소는 null */
        private final Integer transitGapMinutes;
        /** DB places 테이블 기준 좌표 — 미분석 장소는 null */
        private final Double latitude;
        private final Double longitude;
        /** Google raw 카테고리 (예: "cafe", "museum"). AI 미분석 장소도 포함 */
        private final String category;
        /** AI 분석 완료 장소의 PlaceType (FOOD / CAFE / SIGHTS / PARK / MARKET). 미분석 시 null */
        private final PlaceType type;
        /** 실내외 구분 (INDOOR / OUTDOOR / MIX). AI 미분석 시 null — AI 서버 recovery에서 사용 */
        private final String space;
        /** 메모 목록 (생성 시각 오름차순) */
        private final List<MemoResponse> memos;

        private PlaceResponse(TripPlace place, Integer transitGapMinutes,
                              Double latitude, Double longitude,
                              String category, PlaceType type, String space) {
            this.tripPlaceId        = place.getTripPlaceId();
            this.placeId            = place.getPlaceId();
            this.name               = place.getName();
            this.visitTime          = place.getVisitTime();
            this.endTime            = place.getEndTime();
            this.visitOrder         = place.getVisitOrder();
            this.memo               = place.getMemo();
            this.source             = place.getSource();
            this.transportMode      = place.getTransportMode();
            this.transitGapMinutes  = transitGapMinutes;
            this.latitude           = latitude;
            this.longitude          = longitude;
            this.category           = category;
            this.type               = type;
            this.space              = space;
            this.memos              = place.getMemos().stream()
                                          .map(MemoResponse::from)
                                          .collect(Collectors.toList());
        }

        public static PlaceResponse from(TripPlace place, Integer transitGapMinutes,
                                         Double latitude, Double longitude,
                                         String category, PlaceType type, String space) {
            return new PlaceResponse(place, transitGapMinutes, latitude, longitude, category, type, space);
        }
    }
}
