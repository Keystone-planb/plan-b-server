package com.planb.planb_backend.domain.trip.controller;

import com.planb.planb_backend.domain.trip.dto.*;
import com.planb.planb_backend.domain.trip.service.TripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    /**
     * POST /api/trips — 여행 계획 생성
     */
    @PostMapping
    public ResponseEntity<TripSummaryResponse> createTrip(
            @Valid @RequestBody CreateTripRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tripService.createTrip(authentication.getName(), request));
    }

    /**
     * GET /api/trips — 내 여행 목록 조회
     */
    @GetMapping
    public ResponseEntity<List<TripSummaryResponse>> getMyTrips(Authentication authentication) {
        return ResponseEntity.ok(tripService.getMyTrips(authentication.getName()));
    }

    /**
     * GET /api/trips/{id} — 특정 여행 상세 조회
     */
    @GetMapping("/{id}")
    public ResponseEntity<TripDetailResponse> getTripDetail(
            @PathVariable Long id,
            Authentication authentication) {
        return ResponseEntity.ok(tripService.getTripDetail(authentication.getName(), id));
    }

    /**
     * PATCH /api/trips/{id} — 여행 정보 부분 수정
     */
    @PatchMapping("/{id}")
    public ResponseEntity<TripSummaryResponse> updateTrip(
            @PathVariable Long id,
            @RequestBody UpdateTripRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(tripService.updateTrip(authentication.getName(), id, request));
    }

    /**
     * DELETE /api/trips/{id} — 여행 계획 삭제
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTrip(
            @PathVariable Long id,
            Authentication authentication) {
        tripService.deleteTrip(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/trips/{id}/days/{day}/locations — 특정 일차에 장소 추가
     */
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
