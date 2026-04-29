package com.planb.planb_backend.domain.trip.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "itineraries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "itinerary_id")
    private Long itineraryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "trip_id",
        nullable = false,
        foreignKey = @ForeignKey(
            name = "fk_itinerary_trip",
            foreignKeyDefinition = "FOREIGN KEY (trip_id) REFERENCES trips(trip_id) ON DELETE CASCADE"
        )
    )
    private Trip trip;

    @Column(nullable = false)
    private int day;          // 1일차, 2일차 ...

    @Column(nullable = false)
    private LocalDate date;   // 실제 날짜

    @OneToMany(mappedBy = "itinerary", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("visitOrder ASC")
    @Builder.Default
    private List<TripPlace> places = new ArrayList<>();
}
