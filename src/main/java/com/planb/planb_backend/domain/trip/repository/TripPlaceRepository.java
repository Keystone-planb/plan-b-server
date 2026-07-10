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
     * trip.endDate >= today 조건으로 이미 종료된 여행의 일정은 제외
     *
     * JOIN FETCH: itinerary → trip → user 를 한 번의 쿼리로 로딩
     * WeatherScheduler의 @Transactional 없이도 tp.getItinerary().getTrip().getUser() 접근 가능
     */
    @Query("SELECT tp FROM TripPlace tp " +
           "JOIN FETCH tp.itinerary i " +
           "JOIN FETCH i.trip t " +
           "JOIN FETCH t.user u " +
           "WHERE (i.date = :today OR i.date = :tomorrow) " +
           "AND t.endDate >= :today " +
           "ORDER BY i.date ASC, tp.visitTime ASC")
    List<TripPlace> findForScheduler(@Param("today") LocalDate today, @Param("tomorrow") LocalDate tomorrow);

    /**
     * 알림 조회 시 방문 날짜 확인용 — LAZY 로딩 없이 날짜만 조회
     * (NotificationService에서 지난 알림 자동 읽음 처리에 사용)
     */
    @Query("SELECT tp.itinerary.date FROM TripPlace tp WHERE tp.tripPlaceId = :id")
    Optional<LocalDate> findItineraryDateById(@Param("id") Long id);

    /**
     * 알림 응답 생성용 — itinerary + trip 을 한 번의 쿼리로 FETCH
     * @Transactional 없는 컨텍스트에서 getItinerary().getTrip() 접근 시
     * LazyInitializationException 방지용
     */
    @Query("SELECT tp FROM TripPlace tp " +
           "JOIN FETCH tp.itinerary i " +
           "JOIN FETCH i.trip t " +
           "WHERE tp.tripPlaceId = :id")
    Optional<TripPlace> findByIdWithItineraryAndTrip(@Param("id") Long id);

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

    /**
     * [장소 교체 확정] 동일 일차에서 특정 visitOrder 이후 장소 조회 (방문 순서 오름차순)
     * Distance Matrix 기반 시간 재계산 시 사용
     */
    @Query("SELECT tp FROM TripPlace tp " +
           "WHERE tp.itinerary.itineraryId = :itineraryId " +
           "AND tp.visitOrder > :visitOrder " +
           "ORDER BY tp.visitOrder ASC")
    List<TripPlace> findSubsequentInItinerary(@Param("itineraryId") Long itineraryId,
                                              @Param("visitOrder") int visitOrder);

    /**
     * [영향 계산] 동일 일차에서 특정 visitOrder 이전 장소 조회 (방문 순서 내림차순)
     * get(0) 으로 바로 앞 장소 하나만 사용
     */
    @Query("SELECT tp FROM TripPlace tp " +
           "WHERE tp.itinerary.itineraryId = :itineraryId " +
           "AND tp.visitOrder < :visitOrder " +
           "ORDER BY tp.visitOrder DESC")
    List<TripPlace> findPrecedingInItinerary(@Param("itineraryId") Long itineraryId,
                                             @Param("visitOrder") int visitOrder);

}
