package com.planb.planb_backend.domain.trip.repository;

import com.planb.planb_backend.domain.trip.entity.TripPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TripPlaceRepository extends JpaRepository<TripPlace, Long> {

    @Query("SELECT tp FROM TripPlace tp WHERE tp.tripPlaceId = :id AND tp.itinerary.trip.user.email = :email")
    Optional<TripPlace> findByIdAndUserEmail(@Param("id") Long id, @Param("email") String email);

    /**
     * 특정 여행(tripId)에 등록된 모든 TripPlace 조회 (중복 제외용)
     */
    @Query("SELECT tp FROM TripPlace tp WHERE tp.itinerary.trip.tripId = :tripId")
    List<TripPlace> findByTripId(@Param("tripId") Long tripId);

    /**
     * 특정 유저의 오늘 이후 일정을 날짜/방문 시간 오름차순으로 조회
     * (레거시: userId 기준 — 현재는 findUpcomingByTripId 사용)
     */
    @Query("SELECT tp FROM TripPlace tp " +
           "WHERE tp.itinerary.trip.user.id = :userId " +
           "AND tp.itinerary.date >= :today " +
           "ORDER BY tp.itinerary.date ASC, tp.visitTime ASC")
    List<TripPlace> findUpcomingByUserId(@Param("userId") Long userId, @Param("today") LocalDate today);

    /**
     * 특정 여행(tripId) 기준 오늘 이후 일정을 날짜/방문 시간 오름차순으로 조회
     * (다음 목적지 자동 추적 — trip 범위로 한정해 동선 보너스 정확도 향상)
     */
    @Query("SELECT tp FROM TripPlace tp " +
           "WHERE tp.itinerary.trip.tripId = :tripId " +
           "AND tp.itinerary.date >= :today " +
           "ORDER BY tp.itinerary.date ASC, tp.visitTime ASC")
    List<TripPlace> findUpcomingByTripId(@Param("tripId") Long tripId, @Param("today") LocalDate today);

    /**
     * 날씨 스케줄러용 — 오늘/내일 일정 전체 조회
     * (WeatherScheduler에서 24시간 이내 일정 필터링에 사용)
     */
    @Query("SELECT tp FROM TripPlace tp " +
           "WHERE tp.itinerary.date = :today OR tp.itinerary.date = :tomorrow " +
           "ORDER BY tp.itinerary.date ASC, tp.visitTime ASC")
    List<TripPlace> findForScheduler(@Param("today") LocalDate today, @Param("tomorrow") LocalDate tomorrow);

    /**
     * [기능 6 — 틈새 추천] 특정 여행의 모든 일정을 날짜·방문시간 오름차순으로 조회
     * visitOrder(추가 순서)가 아닌 visitTime(실제 방문 시간) 기준으로 정렬해야
     * 시간 역순으로 추가된 일정도 갭 계산이 올바르게 동작함.
     * visitTime이 null인 일정은 맨 뒤로 정렬 (NULLS LAST).
     */
    @Query("SELECT tp FROM TripPlace tp " +
           "WHERE tp.itinerary.trip.tripId = :tripId " +
           "ORDER BY tp.itinerary.date ASC, tp.visitTime ASC NULLS LAST")
    List<TripPlace> findByTripIdOrderByDateAndVisitOrder(@Param("tripId") Long tripId);
}
