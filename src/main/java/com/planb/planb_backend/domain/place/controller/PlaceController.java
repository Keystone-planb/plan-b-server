package com.planb.planb_backend.domain.place.controller;

import com.planb.planb_backend.domain.place.dto.*;
import com.planb.planb_backend.domain.place.dto.PlaceAnalysisStatusResponse;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.PlaceService;
import com.planb.planb_backend.domain.place.service.external.PlaceAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


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
    public ResponseEntity<String> testAnalyze(@RequestParam String googlePlaceId) {
        // DB에 없으면 신규 저장, 있으면 기존 레코드 사용 — 항상 재분석 실행
        Place place = placeRepository.findByGooglePlaceId(googlePlaceId).orElseGet(() -> {
            Place newPlace = new Place();
            newPlace.setGooglePlaceId(googlePlaceId);
            newPlace.setName("분석 중...");
            return placeRepository.saveAndFlush(newPlace);
        });

        try {
            placeAnalysisService.processPlaceAnalysis(place.getId());
            return ResponseEntity.ok("성공적으로 분석하여 DB에 반영했습니다! (ID: " + place.getId() + ")");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("분석 중 에러 발생: " + e.getMessage());
        }
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
     * GET /api/places/{placeId}/analysis-status
     * AI 분석 완료 여부 확인 — 프론트에서 폴링 용도
     */
    @GetMapping("/{placeId}/analysis-status")
    public ResponseEntity<PlaceAnalysisStatusResponse> getAnalysisStatus(@PathVariable String placeId) {
        return ResponseEntity.ok(placeService.getAnalysisStatus(placeId));
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
