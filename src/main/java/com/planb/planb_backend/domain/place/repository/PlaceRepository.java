package com.planb.planb_backend.domain.place.repository;

import com.planb.planb_backend.domain.place.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByGooglePlaceId(String googlePlaceId);

    List<Place> findAllByGooglePlaceIdIn(List<String> googlePlaceIds);

    /** 어드민 통계: AI 분석 완료(type·space·mood 모두 존재) 장소 수 */
    long countByTypeIsNotNullAndSpaceIsNotNullAndMoodIsNotNull();
}
