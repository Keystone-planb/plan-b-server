package com.planb.planb_backend.domain.user.repository;

import com.planb.planb_backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByProviderAndProviderId(String provider, String providerId);

    /** 어드민 시계열: 날짜별 신규 가입자 수 (last N일) */
    @Query(nativeQuery = true,
           value = "SELECT DATE(created_at) AS d, COUNT(*) AS cnt " +
                   "FROM users WHERE created_at >= :from " +
                   "GROUP BY DATE(created_at) ORDER BY d")
    List<Object[]> countDailyNew(@Param("from") LocalDateTime from);
}
