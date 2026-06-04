package com.planb.planb_backend.domain.notification.repository;

import com.planb.planb_backend.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 특정 유저의 미확인 알림 최신순 조회 */
    List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId);

    /** 스케줄러 중복 방지: 동일 planId 알림이 24시간 내에 발송됐는지 확인 (장소 변경 전 호환용) */
    boolean existsByPlanIdAndCreatedAtAfter(Long planId, LocalDateTime after);

    /**
     * 스케줄러 중복 방지 (장소 교체 대응)
     * planId + 좌표가 모두 일치할 때만 중복으로 판정
     * → 같은 슬롯이라도 장소가 바뀌었으면(좌표 다름) 새 알림 허용
     */
    boolean existsByPlanIdAndCreatedAtAfterAndOriginalLatAndOriginalLng(
            Long planId, LocalDateTime after, Double originalLat, Double originalLng);
}
