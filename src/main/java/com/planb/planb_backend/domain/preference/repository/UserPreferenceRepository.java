package com.planb.planb_backend.domain.preference.repository;

import com.planb.planb_backend.domain.preference.entity.UserPreference;
import com.planb.planb_backend.domain.trip.entity.Mood;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserIdAndMood(Long userId, Mood mood);

    List<UserPreference> findByUserId(Long userId);
}
