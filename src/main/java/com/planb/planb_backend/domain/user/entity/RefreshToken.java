package com.planb.planb_backend.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // users 테이블과 1:N 관계 (멀티 세션: 유저당 토큰 여러 개 허용)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 512)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiryDate;

    // 만료 여부 확인 유틸 메서드
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiryDate);
    }
}
