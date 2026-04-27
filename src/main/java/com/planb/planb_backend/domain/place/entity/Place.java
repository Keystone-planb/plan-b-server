package com.planb.planb_backend.domain.place.entity;

import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.entity.Space;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "places")
@Getter
@Setter
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "place_id")
    private Long id;

    @Column(nullable = false)
    private String name;

    private String category;

    // --- [AI 분석 태그] ---
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Space space;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PlaceType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Mood mood;

    // --- [리뷰 데이터 (JSONB)] ---
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String reviewData;

    // --- [구글 정보] ---
    @Column(name = "google_place_id", unique = true, length = 255)
    private String googlePlaceId;

    private Double rating;
    private Integer userRatingsTotal;

    @Column(name = "photo_url", columnDefinition = "text")
    private String photoUrl;

    // --- [좌표] ---
    private Double latitude;
    private Double longitude;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;
}
