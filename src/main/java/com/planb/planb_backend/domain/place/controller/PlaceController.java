package com.planb.planb_backend.domain.place.controller;

import com.planb.planb_backend.domain.place.dto.*;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.PlaceService;
import com.planb.planb_backend.domain.place.service.external.PlaceAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;
    private final PlaceAnalysisService placeAnalysisService;
    private final PlaceRepository placeRepository;

    /**
     * POST /api/places/test/analyze?googlePlaceId=ChIJxxx
     * [테스트용] Google Place ID로 리뷰 수집 + AI 분석 실행
     */
    @PostMapping("/test/analyze")
    public ResponseEntity<Map<String, Object>> testAnalyze(@RequestParam String googlePlaceId) {
        // 1. DB에 장소가 있으면 재사용, 없으면 새로 생성
        Place place = placeRepository.findByGooglePlaceId(googlePlaceId)
                .orElseGet(() -> {
                    Place newPlace = new Place();
                    newPlace.setGooglePlaceId(googlePlaceId);
                    newPlace.setName("분석 중...");
                    return placeRepository.saveAndFlush(newPlace);
                });

        // 2. 리뷰 수집 + AI 분석 실행
        Place result;
        try {
            result = placeAnalysisService.processPlaceAnalysis(place.getId());
        } catch (Exception e) {
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorBody);
        }

        // 3. 결과 반환
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("placeId", result.getId());
        body.put("googlePlaceId", result.getGooglePlaceId());
        body.put("name", result.getName() != null ? result.getName() : "");
        body.put("category", result.getCategory() != null ? result.getCategory() : "");
        body.put("space", result.getSpace() != null ? result.getSpace().name() : "");
        body.put("type", result.getType() != null ? result.getType().name() : "");
        body.put("mood", result.getMood() != null ? result.getMood().name() : "");
        body.put("rating", result.getRating() != null ? result.getRating() : 0.0);
        body.put("reviewData", result.getReviewData() != null ? result.getReviewData() : "");
        body.put("lastSyncedAt", result.getLastSyncedAt() != null ? result.getLastSyncedAt().toString() : "");
        return ResponseEntity.ok(body);
    }

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
