package com.planb.planb_backend.domain.trip.entity;

import com.planb.planb_backend.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trips")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trip_id")
    private Long tripId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Mood Enum 리스트 → 별도 테이블(trip_travel_styles)로 저장
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "trip_travel_styles",
        joinColumns = @JoinColumn(name = "trip_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "mood", length = 20)
    @Builder.Default
    private List<Mood> travelStyles = new ArrayList<>();

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("day ASC")
    @Builder.Default
    private List<Itinerary> itineraries = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // PATCH 용 부분 수정 메서드
    public void update(String title, LocalDate startDate, LocalDate endDate, List<Mood> travelStyles) {
        if (title != null) this.title = title;
        if (startDate != null) this.startDate = startDate;
        if (endDate != null) this.endDate = endDate;
        if (travelStyles != null) {
            this.travelStyles.clear();
            this.travelStyles.addAll(travelStyles);
        }
    }
}
