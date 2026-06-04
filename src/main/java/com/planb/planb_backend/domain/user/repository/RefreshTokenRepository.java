package com.planb.planb_backend.domain.user.repository;

import com.planb.planb_backend.domain.user.entity.RefreshToken;
import com.planb.planb_backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    Optional<RefreshToken> findByUser(User user);

    void deleteByUser(User user);

    /** 로그인 시 해당 유저의 만료된 토큰만 정리 (멀티 세션 lazy cleanup) */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.user = :user AND r.expiryDate < :now")
    void deleteExpiredByUser(@Param("user") User user, @Param("now") LocalDateTime now);
}
