package com.planb.planb_backend.domain.trip.repository;

import com.planb.planb_backend.domain.trip.entity.TripPlaceMemo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TripPlaceMemoRepository extends JpaRepository<TripPlaceMemo, Long> {

    /** 메모 단건 조회 + 소유자 이메일 검증 */
    @Query("SELECT m FROM TripPlaceMemo m WHERE m.id = :id AND m.tripPlace.itinerary.trip.user.email = :email")
    Optional<TripPlaceMemo> findByIdAndUserEmail(@Param("id") Long id, @Param("email") String email);
}
