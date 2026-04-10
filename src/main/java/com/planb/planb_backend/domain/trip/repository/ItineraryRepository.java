package com.planb.planb_backend.domain.trip.repository;

import com.planb.planb_backend.domain.trip.entity.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {
}
