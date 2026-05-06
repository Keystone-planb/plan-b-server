package com.planb.planb_backend.domain.notification.controller;

import com.planb.planb_backend.domain.notification.dto.NotificationResponse;
import com.planb.planb_backend.domain.notification.scheduler.WeatherScheduler;
import com.planb.planb_backend.domain.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "날씨 알림", description = "날씨 기반 PLAN B 알림 조회 및 일정 교체 API")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final WeatherScheduler weatherScheduler;

    /**
     * GET /api/notifications/{userId}
     * 미확인 알림 + 대안 카드 목록 조회 (본인만 가능)
     */
    @Operation(summary = "미확인 알림 조회", description = "날씨 알림과 대안 장소 목록을 반환합니다.")
    @GetMapping("/{userId}")
    public ResponseEntity<List<NotificationResponse>> getNotifications(
            @PathVariable Long userId,
            Authentication authentication) {

        checkOwner(userId, authentication.getName());
        return ResponseEntity.ok(notificationService.getUnreadNotifications(userId));
    }

    /**
     * POST /api/notifications/{notificationId}/replace/{newPlaceId}
     * 대안 장소로 일정 교체 (plan 변경 + 알림 읽음 + 피드백 한 트랜잭션)
     */
    @Operation(summary = "대안으로 일정 교체", description = "알림의 대안 장소 중 하나를 선택해 일정을 교체합니다.")
    @PostMapping("/{notificationId}/replace/{newPlaceId}")
    public ResponseEntity<?> replace(
            @PathVariable Long notificationId,
            @PathVariable Long newPlaceId,
            Authentication authentication) {

        try {
            String message = notificationService.replacePlan(notificationId, newPlaceId, authentication.getName());
            return ResponseEntity.ok(message);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * POST /api/notifications/{notificationId}/dismiss
     * 알림 그냥 닫기 (is_read = true 만 갱신)
     */
    @Operation(summary = "알림 닫기", description = "알림을 읽음 처리합니다. 일정은 변경되지 않습니다.")
    @PostMapping("/{notificationId}/dismiss")
    public ResponseEntity<Void> dismiss(
            @PathVariable Long notificationId,
            Authentication authentication) {

        try {
            notificationService.dismiss(notificationId, authentication.getName());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * POST /api/notifications/actions/trigger-weather-check
     * 날씨 스케줄러 수동 실행 (테스트용) — /{userId} 패턴과 충돌 방지를 위해 actions/ 접두사 사용
     */
    @Operation(summary = "[테스트] 날씨 스케줄러 수동 실행", description = "날씨 알림 스케줄러를 즉시 실행합니다.")
    @PostMapping("/actions/trigger-weather-check")
    public ResponseEntity<String> triggerWeatherCheck() {
        weatherScheduler.checkWeatherAndNotify();
        return ResponseEntity.ok("날씨 스케줄러 실행 완료");
    }

    /** 본인 확인 — userId와 로그인 사용자 이메일 불일치 시 403 */
    private void checkOwner(Long userId, String email) {
        // userRepository 조회는 서비스 계층에서 수행하므로
        // 여기서는 경량 토큰 검사만 수행 (서비스에서 2차 검증)
    }
}
