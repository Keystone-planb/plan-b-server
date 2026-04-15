package com.planb.planb_backend.domain.place.controller;

import com.planb.planb_backend.domain.place.dto.*;
import com.planb.planb_backend.domain.place.service.PlaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;

    /**
     * GET /api/places/search?query=카페&lat=37.5&lng=127.0
     * 장소 검색
     */
    @GetMapping("/search")
    public ResponseEntity<PlaceSearchResponse> searchPlaces(
            @RequestParam String query,
            @RequestParam(defaultValue = "37.5665") double lat,
            @RequestParam(defaultValue = "126.9780") double lng) {
        return ResponseEntity.ok(placeService.searchPlaces(query, lat, lng));
    }

    /**
     * GET /api/places/{placeId}
     * 장소 상세 정보 조회
     */
    @GetMapping("/{placeId}")
    public ResponseEntity<PlaceDetailResponse> getPlaceDetail(@PathVariable String placeId) {
        return ResponseEntity.ok(placeService.getPlaceDetail(placeId));
    }

    /**
     * GET /api/places/{placeId}/summary
     * 장소 AI 요약
     */
    @GetMapping("/{placeId}/summary")
    public ResponseEntity<PlaceSummaryResponse> getPlaceSummary(@PathVariable String placeId) {
        return ResponseEntity.ok(placeService.getPlaceSummary(placeId));
    }

    /**
     * GET /api/places/{placeId}/freshness
     * 장소 정보 최신성 확인
     */
    @GetMapping("/{placeId}/freshness")
    public ResponseEntity<PlaceFreshnessResponse> getPlaceFreshness(@PathVariable String placeId) {
        return ResponseEntity.ok(placeService.getPlaceFreshness(placeId));
    }
}
