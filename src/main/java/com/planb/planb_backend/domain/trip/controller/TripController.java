package com.planb.planb_backend.domain.trip.controller;

import com.planb.planb_backend.domain.trip.dto.*;
import com.planb.planb_backend.domain.trip.dto.TripDetailResponse;
import com.planb.planb_backend.domain.trip.entity.TransportMode;
import com.planb.planb_backend.domain.trip.service.TripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "여행 계획", description = "여행 일정 생성 및 관리 API")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @Operation(summary = "여행 계획 생성", description = "새로운 여행 계획을 생성합니다. 시작일~종료일 기준으로 일차(Itinerary)가 자동 생성됩니다.")
    @PostMapping
    public ResponseEntity<TripSummaryResponse> createTrip(
            @Valid @RequestBody CreateTripRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tripService.createTrip(authentication.getName(), request));
    }

    @Operation(
        summary = "내 여행 목록 조회",
        description = "현재 로그인한 유저의 여행 목록을 상태별로 조회합니다. " +
                      "status: UPCOMING(예정된 여행), PAST(지난 여행), ALL(전체, 기본값)"
    )
    @GetMapping
    public ResponseEntity<List<TripListResponse>> getMyTrips(
            @Parameter(description = "여행 상태 필터 (UPCOMING / PAST / ALL)", example = "ALL")
            @RequestParam(defaultValue = "ALL") String status,
            Authentication authentication) {
        return ResponseEntity.ok(tripService.getMyTrips(authentication.getName(), status));
    }

    @Operation(summary = "여행 상세 조회", description = "특정 여행의 전체 일차와 장소 목록을 반환합니다.")
    @GetMapping("/{id}")
    public ResponseEntity<TripDetailResponse> getTripDetail(
            @PathVariable Long id,
            Authentication authentication) {
        return ResponseEntity.ok(tripService.getTripDetail(authentication.getName(), id));
    }

    @Operation(summary = "여행 정보 수정", description = "여행 제목, 날짜, 여행 스타일을 부분 수정합니다.")
    @PatchMapping("/{id}")
    public ResponseEntity<TripSummaryResponse> updateTrip(
            @PathVariable Long id,
            @RequestBody UpdateTripRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(tripService.updateTrip(authentication.getName(), id, request));
    }

    @Operation(summary = "여행 삭제", description = "여행 계획과 모든 일정을 삭제합니다.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTrip(
            @PathVariable Long id,
            Authentication authentication) {
        tripService.deleteTrip(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "일정에 장소 추가", description = "특정 여행의 N일차에 장소를 추가합니다.")
    @PostMapping("/{id}/days/{day}/locations")
    public ResponseEntity<AddLocationResponse> addLocation(
            @PathVariable Long id,
            @PathVariable int day,
            @Valid @RequestBody AddLocationRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tripService.addLocation(authentication.getName(), id, day, request));
    }

    @Operation(summary = "특정 일차 조회", description = "N일차의 장소 목록을 조회합니다.")
    @GetMapping("/{id}/days/{day}")
    public ResponseEntity<TripDetailResponse.ItineraryResponse> getDayDetail(
            @PathVariable Long id,
            @PathVariable int day,
            Authentication authentication) {
        return ResponseEntity.ok(tripService.getDayDetail(authentication.getName(), id, day));
    }

    @Operation(summary = "특정 일차 장소 전체 삭제", description = "N일차의 장소를 전부 삭제합니다. 일차(날짜)는 유지됩니다.")
    @DeleteMapping("/{id}/days/{day}/locations")
    public ResponseEntity<Void> clearDayLocations(
            @PathVariable Long id,
            @PathVariable int day,
            Authentication authentication) {
        tripService.clearDayLocations(authentication.getName(), id, day);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "이동 수단 조회", description = "여행의 주 이동 수단을 조회합니다. (WALK / TRANSIT / CAR)")
    @GetMapping("/{id}/transport-mode")
    public ResponseEntity<TransportMode> getTransportMode(
            @PathVariable Long id,
            Authentication authentication) {
        return ResponseEntity.ok(tripService.getTransportMode(authentication.getName(), id));
    }

    @Operation(summary = "이동 수단 수정", description = "여행의 주 이동 수단을 변경합니다. 변경 후 모든 추천에 자동 적용됩니다.")
    @PatchMapping("/{id}/transport-mode")
    public ResponseEntity<Void> updateTransportMode(
            @PathVariable Long id,
            @RequestParam TransportMode mode,
            Authentication authentication) {
        tripService.updateTransportMode(authentication.getName(), id, mode);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "날씨 복구 확정",
            description = "AI 날씨 복구를 사용자가 승인했을 때 변경된 장소·시간을 DB에 저장합니다. " +
                          "recovery_done 이벤트의 places 배열을 그대로 전달하면 됩니다."
    )
    @PostMapping("/{tripId}/days/{day}/recovery/confirm")
    public ResponseEntity<Void> confirmRecovery(
            @PathVariable Long tripId,
            @PathVariable Integer day,
            @Valid @RequestBody RecoveryConfirmRequest request,
            Authentication authentication) {
        tripService.confirmRecovery(authentication.getName(), tripId, day, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "대안 장소 선택 확정",
            description = "AI 동선 최적화에서 사용자가 선택한 대안 장소를 확정합니다. " +
                          "교체 대상 장소의 placeId·name을 변경하고, 이후 장소들의 방문 시간을 일괄 업데이트합니다."
    )
    @PostMapping("/{tripId}/days/{day}/places/{tripPlaceId}/optimize/confirm")
    public ResponseEntity<Void> confirmOptimize(
            @PathVariable Long tripId,
            @PathVariable Integer day,
            @PathVariable Long tripPlaceId,
            @Valid @RequestBody OptimizeConfirmRequest request,
            Authentication authentication) {
        tripService.confirmOptimize(authentication.getName(), tripPlaceId, request);
        return ResponseEntity.noContent().build();
    }
}
