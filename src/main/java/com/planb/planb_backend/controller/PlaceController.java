package com.planb.planb_backend.controller;

import com.planb.planb_backend.dto.PlaceSearchResponseDto;
import com.planb.planb_backend.service.GoogleMapsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 장소 검색 API 엔드포인트
 * GET /api/v1/places/search?query=강남역
 */
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceController {

    private final GoogleMapsService googleMapsService;

    /**
     * 장소 검색
     *
     * @param query 검색어 (예: 강남역, 홍대 카페)
     * @return 장소 목록
     */
    @GetMapping("/search")
    public ResponseEntity<List<PlaceSearchResponseDto>> searchPlaces(
            @RequestParam String query) {

        List<PlaceSearchResponseDto> result = googleMapsService.searchPlaces(query);
        return ResponseEntity.ok(result);
    }
}
