package com.planb.planb_backend.domain.trip.repository;

import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    // 로그인한 유저의 여행 목록 (최신순)
    List<Trip> findByUserOrderByCreatedAtDesc(User user);

    // 소유자 검증 포함 단건 조회 (보안)
    Optional<Trip> findByTripIdAndUser(Long tripId, User user);

    /**
     * 홈 목록 조회 전용: 이티너리를 JOIN FETCH해 itineraryCount·placeCount 계산 시 N+1 방지
     * places는 이티너리별 lazy load (트랜잭션 내에서 안전하게 동작)
     */
    @Query("""
        SELECT DISTINCT t FROM Trip t
        LEFT JOIN FETCH t.itineraries
        WHERE t.user = :user
        ORDER BY t.createdAt DESC
        """)
    List<Trip> findByUserWithItineraries(@Param("user") User user);
}
