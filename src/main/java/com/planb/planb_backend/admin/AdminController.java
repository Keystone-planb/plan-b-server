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

    // ── 사용자 관리 ─────────────────────────────────────────────────────────

    @Operation(summary = "전체 사용자 목록 조회")
    @GetMapping("/users")
    public ResponseEntity<List<AdminService.AdminUserDto>> getAllUsers() {
        return ResponseEntity.ok(adminService.getAllUsers());
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

    @Operation(summary = "DB 장소 전체 목록 조회 (분석 상태 포함)")
    @GetMapping("/places")
    public ResponseEntity<List<AdminService.AdminPlaceDto>> getAllPlaces() {
        return ResponseEntity.ok(adminService.getAllPlaces());
    }

    // ── 무드 선호도 조회 ───────────────────────────────────────────────────

    @Operation(summary = "특정 사용자의 무드 선호도 조회")
    @GetMapping("/users/{userId}/preferences")
    public ResponseEntity<List<AdminService.AdminMoodPreferenceDto>> getUserMoodPreferences(
            @PathVariable Long userId) {
        return ResponseEntity.ok(adminService.getUserMoodPreferences(userId));
    }

    // ── 알림 관제 ──────────────────────────────────────────────────────────

    @Operation(summary = "날씨 알림 전체 목록 조회 (사용자·장소·여행 정보 포함, 최신순)")
    @GetMapping("/notifications")
    public ResponseEntity<List<AdminService.AdminNotificationDto>> getAllNotifications() {
        return ResponseEntity.ok(adminService.getAllNotifications());
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
