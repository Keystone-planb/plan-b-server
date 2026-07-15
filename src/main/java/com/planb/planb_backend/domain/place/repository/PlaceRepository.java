package com.planb.planb_backend.domain.place.repository;

import com.planb.planb_backend.domain.place.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByGooglePlaceId(String googlePlaceId);

    List<Place> findAllByGooglePlaceIdIn(List<String> googlePlaceIds);

    /** 어드민 통계: AI 분석 완료(type·space·mood 모두 존재) 장소 수 */
    long countByTypeIsNotNullAndSpaceIsNotNullAndMoodIsNotNull();

    /** 어드민 시계열: 날짜별 AI 장소 분석 완료 수 (last_synced_at 기준) */
    @Query(nativeQuery = true,
           value = "SELECT DATE(last_synced_at) AS d, COUNT(*) AS cnt " +
                   "FROM places WHERE last_synced_at >= :from " +
                   "AND type IS NOT NULL AND space IS NOT NULL AND mood IS NOT NULL " +
                   "GROUP BY DATE(last_synced_at) ORDER BY d")
    List<Object[]> countDailyAnalyzed(@Param("from") LocalDateTime from);
}
