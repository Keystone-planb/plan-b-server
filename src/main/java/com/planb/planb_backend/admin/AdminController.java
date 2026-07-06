package com.planb.planb_backend.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "어드민", description = "내부 관리자 전용 API — ADMIN 권한 JWT 필요")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // ── 스케줄러 수동 실행 ────────────────────────────────────────────────────

    @Operation(
        summary = "날씨 스케줄러 수동 실행",
        description = "24시간 이내 일정을 조회하여 날씨 알림을 즉시 발송합니다. 완료까지 수 초~수십 초 소요될 수 있습니다."
    )
    @PostMapping("/scheduler/weather")
    public ResponseEntity<Map<String, String>> triggerWeatherScheduler() {
        adminService.triggerWeatherScheduler();
        return ResponseEntity.ok(Map.of("message", "날씨 스케줄러 실행 완료"));
    }

    // ── 대시보드 통계 ─────────────────────────────────────────────────────────

    @Operation(summary = "대시보드 요약 통계 조회")
    @GetMapping("/stats")
    public ResponseEntity<AdminService.AdminStatsDto> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    // ── 사용자 관리 ─────────────────────────────────────────────────────────

    @Operation(summary = "전체 사용자 목록 조회 (페이지네이션)")
    @GetMapping("/users")
    public ResponseEntity<AdminService.AdminPageResponse<AdminService.AdminUserDto>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(adminService.getAllUsers(page, size));
    }

    @Operation(summary = "사용자 강제 삭제", description = "FK 역방향 순차 삭제로 외래키 오류 없이 안전하게 삭제합니다.")
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long userId) {
        adminService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    // ── 여행 관리 ─────────────────────────────────────────────────────────

    @Operation(summary = "특정 사용자의 여행 목록 조회")
    @GetMapping("/users/{userId}/trips")
    public ResponseEntity<List<AdminService.AdminTripDto>> getTripsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(adminService.getTripsByUser(userId));
    }

    @Operation(summary = "여행 강제 삭제", description = "연결된 알림 → Itinerary → TripPlace → TripPlaceMemo 순으로 안전하게 삭제합니다.")
    @DeleteMapping("/trips/{tripId}")
    public ResponseEntity<Void> deleteTrip(@PathVariable Long tripId) {
        adminService.deleteTrip(tripId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "특정 여행의 장소 목록 조회")
    @GetMapping("/trips/{tripId}/places")
    public ResponseEntity<List<AdminService.AdminTripPlaceDto>> getPlacesByTrip(@PathVariable Long tripId) {
        return ResponseEntity.ok(adminService.getPlacesByTrip(tripId));
    }

    @Operation(summary = "일정 장소 단건 강제 삭제", description = "연결된 알림 → TripPlaceMemo 순으로 안전하게 삭제합니다.")
    @DeleteMapping("/trip-places/{tripPlaceId}")
    public ResponseEntity<Void> deleteTripPlace(@PathVariable Long tripPlaceId) {
        adminService.deleteTripPlace(tripPlaceId);
        return ResponseEntity.noContent().build();
    }

    // ── 장소 관리 ─────────────────────────────────────────────────────────

    @Operation(summary = "DB 장소 전체 목록 조회 (분석 상태 포함, 페이지네이션)")
    @GetMapping("/places")
    public ResponseEntity<AdminService.AdminPageResponse<AdminService.AdminPlaceDto>> getAllPlaces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(adminService.getAllPlaces(page, size));
    }

    // ── 무드 선호도 조회 ───────────────────────────────────────────────────

    @Operation(summary = "특정 사용자의 무드 선호도 조회")
    @GetMapping("/users/{userId}/preferences")
    public ResponseEntity<List<AdminService.AdminMoodPreferenceDto>> getUserMoodPreferences(
            @PathVariable Long userId) {
        return ResponseEntity.ok(adminService.getUserMoodPreferences(userId));
    }

    // ── 즐겨찾기 관리 ─────────────────────────────────────────────────────

    @Operation(summary = "전체 즐겨찾기 목록 조회 (사용자 정보 포함, 최신순, 페이지네이션)")
    @GetMapping("/bookmarks")
    public ResponseEntity<AdminService.AdminPageResponse<AdminService.AdminBookmarkDto>> getAllBookmarks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(adminService.getAllBookmarks(page, size));
    }

    @Operation(summary = "즐겨찾기 강제 삭제")
    @DeleteMapping("/bookmarks/{bookmarkId}")
    public ResponseEntity<Void> deleteBookmark(@PathVariable Long bookmarkId) {
        adminService.deleteBookmark(bookmarkId);
        return ResponseEntity.noContent().build();
    }

    // ── 알림 관제 ──────────────────────────────────────────────────────────

    @Operation(summary = "날씨 알림 전체 목록 조회 (사용자·장소·여행 정보 포함, 최신순, 페이지네이션)")
    @GetMapping("/notifications")
    public ResponseEntity<AdminService.AdminPageResponse<AdminService.AdminNotificationDto>> getAllNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(adminService.getAllNotifications(page, size));
    }

    @Operation(
        summary = "날씨 알림 수동 재발송",
        description = "특정 알림을 해당 유저에게 즉시 다시 발송하고 push_sent_at을 현재 시각으로 갱신합니다."
    )
    @PostMapping("/notifications/{notificationId}/resend")
    public ResponseEntity<Map<String, String>> resendNotification(
            @PathVariable Long notificationId) {
        adminService.resendNotification(notificationId);
        return ResponseEntity.ok(Map.of("message", "재발송 완료"));
    }
}
