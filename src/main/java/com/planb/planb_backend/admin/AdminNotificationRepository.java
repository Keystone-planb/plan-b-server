package com.planb.planb_backend.admin;

import com.planb.planb_backend.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 어드민 전용 Notification 삭제 쿼리
 * 기존 NotificationRepository 에 없는 삭제 메서드만 추가
 */
public interface AdminNotificationRepository extends JpaRepository<Notification, Long> {

    /** 사용자 탈퇴 시: 해당 유저의 알림 전체 삭제 */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /** Trip 강제 삭제 시: 삭제될 TripPlace(planId)에 연결된 알림 삭제 */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.planId IN :planIds")
    void deleteByPlanIdIn(@Param("planIds") List<Long> planIds);

    /** 어드민 알림 관제: 최신순 전체 조회 */
    List<Notification> findAllByOrderByCreatedAtDesc();
}
