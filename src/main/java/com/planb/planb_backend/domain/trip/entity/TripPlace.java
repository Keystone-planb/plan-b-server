package com.planb.planb_backend.domain.trip.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "trip_places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class TripPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trip_place_id")
    private Long tripPlaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Column(name = "place_id", nullable = false, length = 100)
    private String placeId;     // Google Place ID

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "visit_time", length = 10)
    private String visitTime;   // "09:00" 형식

    @Column(name = "visit_order")
    private int visitOrder;     // 방문 순서
}
