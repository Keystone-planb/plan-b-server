package com.planb.planb_backend.domain.preference.controller;

import com.planb.planb_backend.domain.preference.dto.FeedbackRequest;
import com.planb.planb_backend.domain.preference.dto.PreferenceSummaryResponse;
import com.planb.planb_backend.domain.preference.service.PreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "개인화 학습", description = "사용자 취향(mood) 피드백 및 요약 API")
@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService preferenceService;

    /**
     * POST /api/preferences/feedback
     * 추천 결과에 대한 명시적 피드백 보고
     * - 선택된 장소 mood +1.0
     * - 노출됐지만 미선택 장소 mood -0.3
     */
    @Operation(summary = "피드백 보고", description = "추천 결과에서 선택/미선택 정보를 학습합니다.")
    @PostMapping("/feedback")
    public ResponseEntity<Void> feedback(@RequestBody FeedbackRequest request) {
        preferenceService.applyFeedback(
                request.getUserId(),
                request.getShownPlaceIds(),
                request.getSelectedPlaceId()
        );
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/preferences/{userId}/summary
     * 취향 한 줄 요약 조회
     */
    @Operation(summary = "취향 요약 조회", description = "누적 피드백을 기반으로 사용자 취향을 한 줄로 요약합니다.")
    @GetMapping("/{userId}/summary")
    public ResponseEntity<PreferenceSummaryResponse> summary(@PathVariable Long userId) {
        return ResponseEntity.ok(preferenceService.getSummary(userId));
    }
}
