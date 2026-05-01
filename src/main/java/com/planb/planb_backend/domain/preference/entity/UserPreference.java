package com.planb.planb_backend.domain.preference.entity;

import com.planb.planb_backend.domain.trip.entity.Mood;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "user_preferences",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "mood"})
)
@Getter
@Setter
@NoArgsConstructor
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** mood 속성만 학습 대상 (space, type 제외) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Mood mood;

    /**
     * 누적 점수
     * - 선택됨: +1.0
     * - 노출되었지만 미선택: -0.3
     */
    @Column(nullable = false)
    private Double score = 0.0;

    public UserPreference(Long userId, Mood mood) {
        this.userId = userId;
        this.mood   = mood;
        this.score  = 0.0;
    }
}
