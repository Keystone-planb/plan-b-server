package com.planb.planb_backend.domain.trip.repository;

import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    // 로그인한 유저의 여행 목록 (최신순)
    List<Trip> findByUserOrderByCreatedAtDesc(User user);

    // 소유자 검증 포함 단건 조회 (보안)
    Optional<Trip> findByTripIdAndUser(Long tripId, User user);
}
