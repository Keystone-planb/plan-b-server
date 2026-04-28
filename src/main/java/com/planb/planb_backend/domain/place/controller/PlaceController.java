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
        // 1. 이미 분석된 장소면 DB 캐시 반환 (재분석 스킵)
        Place existing = placeRepository.findByGooglePlaceId(googlePlaceId).orElse(null);
        if (existing != null && existing.getReviewData() != null && !existing.getReviewData().isBlank()) {
            return ResponseEntity.ok(toResponseBody(existing));
        }

        // 2. 신규 장소면 DB에 저장 후 분석 실행
        Place place = (existing != null) ? existing : placeRepository.saveAndFlush(
                new Place() {{ setGooglePlaceId(googlePlaceId); setName("분석 중..."); }}
        );

        Place result;
        try {
            result = placeAnalysisService.processPlaceAnalysis(place.getId());
        } catch (Exception e) {
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorBody);
        }

        // 3. 결과 반환
        return ResponseEntity.ok(toResponseBody(result));
    }

    private Map<String, Object> toResponseBody(Place p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("placeId", p.getId());
        body.put("googlePlaceId", p.getGooglePlaceId());
        body.put("name", p.getName() != null ? p.getName() : "");
        body.put("category", p.getCategory() != null ? p.getCategory() : "");
        body.put("space", p.getSpace() != null ? p.getSpace().name() : "");
        body.put("type", p.getType() != null ? p.getType().name() : "");
        body.put("mood", p.getMood() != null ? p.getMood().name() : "");
        body.put("rating", p.getRating() != null ? p.getRating() : 0.0);
        body.put("reviewData", p.getReviewData() != null ? p.getReviewData() : "");
        body.put("lastSyncedAt", p.getLastSyncedAt() != null ? p.getLastSyncedAt().toString() : "");
        return body;
    }

    /**
     * GET /api/places/search?query=광화문 스타벅스
     * 장소명으로 검색
     */
    @GetMapping("/search")
    public ResponseEntity<PlaceSearchResponse> searchPlaces(@RequestParam String query) {
        return ResponseEntity.ok(placeService.searchPlaces(query));
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
