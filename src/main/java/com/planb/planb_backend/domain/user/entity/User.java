package com.planb.planb_backend.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "users",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_provider_provider_id",
        columnNames = {"provider", "provider_id"}
    )
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    // 소셜 가입자는 비밀번호 없으므로 Nullable
    @Column(length = 200)
    private String password;

    @Column(nullable = false, length = 50)
    private String nickname;

    // 가입 출처: local / google / kakao
    @Column(nullable = false, length = 20)
    private String provider;

    // 소셜 서비스 고유 식별자
    @Column(name = "provider_id", length = 100)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // ACTIVE / WITHDRAWN
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    // AI 추천용 취향: 여행 스타일 (예: HEALING, ACTIVE, TRENDY, CLASSIC, LOCAL)
    @Column(name = "travel_style", length = 50)
    private String travelStyle;

    // AI 추천용 취향: 선호 장소 유형 목록 (JSON 배열 문자열로 저장, 예: ["FOOD","CAFE","PARK"])
    @Column(name = "preferences", columnDefinition = "text")
    private String preferences;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void withdraw() {
        this.status = "WITHDRAWN";
    }

    public void updatePreferences(String travelStyle, String preferences) {
        this.travelStyle = travelStyle;
        this.preferences = preferences;
    }
}
