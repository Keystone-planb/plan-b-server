package com.planb.planb_backend.domain.trip.controller;

import com.planb.planb_backend.domain.place.dto.GapInfo;
import com.planb.planb_backend.domain.place.dto.GapRecommendationRequest;
import com.planb.planb_backend.domain.place.service.external.GapDetectionService;
import com.planb.planb_backend.domain.place.service.external.GapRecommendationService;
import com.planb.planb_backend.domain.trip.entity.TransportMode;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * [기능 6 — 틈새 추천] 갭 목록 조회 + 특정 갭에 대한 SSE 스트리밍 추천
 *
 *  GET  /api/trips/{tripId}/gaps                      → 갭 목록 (UI 노출용)
 *  POST /api/trips/{tripId}/gaps/recommend/stream     → 특정 갭 SSE 스트리밍 추천
 */
@Slf4j
@Tag(name = "틈새 추천", description = "일정 사이 빈 시간(갭) 감지 및 장소 추천 API")
@RestController
@RequestMapping("/api/trips/{tripId}/gaps")
@RequiredArgsConstructor
public class GapController {

    private final GapDetectionService gapDetectionService;
    private final GapRecommendationService gapRecommendationService;
    private final UserRepository userRepository;

    /**
     * GET /api/trips/{tripId}/gaps
     * <p>
     * mode 미지정 시 trip.transportMode 기준으로 availableMinutes 를 계산한다.
     * UI 가 이동수단 탭을 두고 싶다면 ?mode=WALK|TRANSIT|CAR 로 재요청.
     */
    @Operation(
        summary = "갭 목록 조회",
        description = "여행 일정 사이에서 30분 이상 비는 시간 목록과 활용 가능한 시간을 반환합니다."
    )
    @GetMapping
    public ResponseEntity<List<GapInfo>> listGaps(
            @PathVariable Long tripId,
            @RequestParam(required = false) TransportMode mode) {
        return ResponseEntity.ok(gapDetectionService.detectGaps(tripId, mode));
    }

    /**
     * POST /api/trips/{tripId}/gaps/recommend/stream
     * <p>
     * SSE 스트리밍 방식으로 틈새 장소를 추천한다.
     * progress → place(×N) → done 순으로 이벤트가 전송된다.
     * <p>
     * 프론트엔드 구현:
     *   fetch('/api/trips/{tripId}/gaps/recommend/stream', {
     *     method: 'POST',
     *     headers: { 'Authorization': 'Bearer {token}', 'Content-Type': 'application/json' },
     *     body: JSON.stringify({ beforePlanId: 1, afterPlanId: 2 })
     *   })
     */
    @Operation(
        summary = "틈새 장소 추천 (SSE 스트리밍)",
        description = "두 일정 사이의 갭에 맞는 장소를 실시간으로 추천합니다. " +
                      "progress → place(×최대5) → done 순서로 이벤트가 전송됩니다."
    )
    @PostMapping(value = "/recommend/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamRecommend(
            @PathVariable Long tripId,
            @RequestBody GapRecommendationRequest req,
            Authentication authentication,
            HttpServletResponse response) {

        response.setCharacterEncoding("UTF-8");

        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (req.getTripId() == null) req.setTripId(tripId);
        req.setUserId(user.getId());

        log.info("[GapController] tripId={}, beforePlanId={}, afterPlanId={}",
                tripId, req.getBeforePlanId(), req.getAfterPlanId());

        return gapRecommendationService.streamRecommendForGap(req);
    }
}
