package com.planb.planb_backend.domain.trip.controller;

import com.planb.planb_backend.domain.trip.dto.*;
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
}
