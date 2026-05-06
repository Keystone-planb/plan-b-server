package com.planb.planb_backend.domain.notification.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** TripPlace.tripPlaceId */
    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 알림 유형 (현재: WEATHER_RAIN) */
    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** 강수 확률 0~100 */
    @Column(name = "precipitation_prob")
    private Integer precipitationProb;

    /** 대안 장소 DB PK 목록 (jsonb) — ex) [201, 202, 203] */
    @Column(name = "alternative_place_ids", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String alternativePlaceIds;

    /** 원래 일정 장소 좌표 — 알림 조회 시 실시간 근처 탐색에 사용 */
    @Column(name = "original_lat")
    private Double originalLat;

    @Column(name = "original_lng")
    private Double originalLng;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
