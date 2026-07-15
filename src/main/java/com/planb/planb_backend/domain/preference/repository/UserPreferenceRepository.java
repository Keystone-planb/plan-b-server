package com.planb.planb_backend.domain.preference.repository;

import com.planb.planb_backend.domain.preference.entity.UserPreference;
import com.planb.planb_backend.domain.trip.entity.Mood;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserIdAndMood(Long userId, Mood mood);

    List<UserPreference> findByUserId(Long userId);

    /** 어드민 DNA 분석: Mood별 평균점수·총 유저수·긍정·부정 집계 */
    @Query("SELECT up.mood, AVG(up.score), COUNT(up), " +
           "SUM(CASE WHEN up.score > 0 THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN up.score <= 0 THEN 1 ELSE 0 END) " +
           "FROM UserPreference up GROUP BY up.mood ORDER BY AVG(up.score) DESC")
    List<Object[]> findMoodStats();
}
