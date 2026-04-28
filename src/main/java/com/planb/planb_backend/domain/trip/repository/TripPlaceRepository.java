package com.planb.planb_backend.domain.trip.repository;

import com.planb.planb_backend.domain.trip.entity.TripPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TripPlaceRepository extends JpaRepository<TripPlace, Long> {

    @Query("SELECT tp FROM TripPlace tp WHERE tp.tripPlaceId = :id AND tp.itinerary.trip.user.email = :email")
    Optional<TripPlace> findByIdAndUserEmail(@Param("id") Long id, @Param("email") String email);
}
