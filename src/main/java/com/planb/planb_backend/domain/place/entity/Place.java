package com.planb.planb_backend.domain.place.entity;

import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.entity.Space;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

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

    /**
     * PostGIS 공간 좌표 컬럼 (EPSG:4326 — WGS84)
     * - 향후 공간 인덱스 기반 반경 검색 최적화에 사용 (ST_DWithin 등)
     * - 현재는 latitude/longitude 기반 Haversine 사용 중, 마이그레이션 예정
     * - hibernate-spatial 의존성으로 JTS Point 타입 자동 매핑
     */
    @Column(columnDefinition = "geometry(Point, 4326)")
    private Point location;

    // --- [영업 정보] ---
    @Enumerated(EnumType.STRING)
    @Column(name = "business_status", length = 30)
    private BusinessStatus businessStatus;

    @Column(name = "opening_hours", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String openingHours;     // {"weekday_text": [...], "periods": [...]}

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(length = 255)
    private String website;

    @Column(name = "price_level")
    private Integer priceLevel;      // 0(무료) ~ 4(매우 비쌈)

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;
}
