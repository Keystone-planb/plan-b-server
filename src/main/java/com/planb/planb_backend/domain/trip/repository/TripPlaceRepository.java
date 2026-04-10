package com.planb.planb_backend.domain.trip.repository;

import com.planb.planb_backend.domain.trip.entity.TripPlace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripPlaceRepository extends JpaRepository<TripPlace, Long> {
}
