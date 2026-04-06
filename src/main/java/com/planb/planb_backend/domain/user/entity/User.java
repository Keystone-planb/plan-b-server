package com.planb.planb_backend.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", length = 100, nullable = false, unique = true)
    private String email;

    // 소셜 유저는 비밀번호가 없으므로 nullable
    @Column(name = "password", length = 200)
    private String password;

    @Column(name = "nickname", length = 50, nullable = false)
    private String nickname;

    // LOCAL / GOOGLE / KAKAO
    @Column(name = "provider", length = 20, nullable = false)
    private String provider;

    // 소셜 로그인 시 플랫폼에서 받은 고유 ID
    @Column(name = "provider_id", length = 100)
    private String providerId;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
