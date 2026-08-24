package com.planb.planb_backend.domain.place.repository;

import com.planb.planb_backend.domain.place.entity.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {
    List<AiCallLog> findByCreatedAtAfter(LocalDateTime since);
}
