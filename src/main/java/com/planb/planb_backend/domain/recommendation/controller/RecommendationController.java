package com.planb.planb_backend.domain.recommendation.controller;

import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.service.external.PlaceAnalysisService;
import com.planb.planb_backend.domain.place.service.external.RecommendationService;
import com.planb.planb_backend.domain.recommendation.dto.*;
import com.planb.planb_backend.domain.trip.dto.AddLocationResponse;
import com.planb.planb_backend.domain.trip.dto.UpdateScheduleRequest;
import com.planb.planb_backend.domain.trip.service.TripService;
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

import java.util.List;

@Slf4j
@Tag(name = "대안 추천", description = "PLAN B 핵심 — 장소 추천 / 분석 / 일정 대체 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final PlaceAnalysisService placeAnalysisService;
    private final TripService tripService;
    private final UserRepository userRepository;

    /**
     * POST /api/recommendations
     * 현재 위치와 이동 조건을 기반으로 대안 장소 최대 5개 추천
     */
    @Operation(
        summary = "대안 장소 추천",
        description = "현재 위치와 이동 조건을 기반으로 AI 분석된 대안 장소를 최대 5개 추천합니다."
    )
    @PostMapping("/recommendations")
    public ResponseEntity<RecommendResponse> recommend(
            @RequestBody RecommendRequest request,
            Authentication authentication) {

        User user = findUser(authentication.getName());

        UserContext context = UserContext.builder()
                .userId(user.getId())
                .tripId(request.getTripId())
                .currentPlanId(request.getCurrentPlanId())
                .currentLat(request.getCurrentLat())
                .currentLng(request.getCurrentLng())
                .radiusMinute(request.getRadiusMinute())
                .walk(request.isWalk())
                .selectedSpace(request.getSelectedSpace())
                .selectedType(request.getSelectedType())
                .keepOriginalCategory(request.isKeepOriginalCategory())
                .considerNextPlan(request.isConsiderNextPlan())
                .nextLat(request.getNextLat())
                .nextLng(request.getNextLng())
                .build();

        List<Place> places = recommendationService.getRecommendations(context);

        List<PlaceResult> results = places.stream()
                .map(PlaceResult::from)
                .toList();

        return ResponseEntity.ok(RecommendResponse.builder()
                .recommendations(results)
                .totalCount(results.size())
                .build());
    }

    /**
     * POST /api/recommendations/stream
     * SSE 스트리밍 방식 대안 장소 추천
     * - 연결 즉시 progress 이벤트 → 스켈레톤 UI 렌더링
     * - 분석 완료 순서대로 place 이벤트 push (최대 5개)
     * - 전체 완료 시 done 이벤트 → 클라이언트 연결 종료
     *
     * 프론트엔드 구현 참고:
     *   fetch('/api/recommendations/stream', {
     *     method: 'POST',
     *     headers: { 'Authorization': 'Bearer {token}', 'Content-Type': 'application/json' },
     *     body: JSON.stringify(request)
     *   }) 로 수신 (EventSource 미사용 — Authorization 헤더 전송 불가)
     */
    @Operation(
        summary = "대안 장소 추천 (SSE 스트리밍)",
        description = "분석이 완료된 장소부터 1개씩 실시간으로 push합니다. " +
                      "progress → place(×N) → done 순서로 이벤트가 전송됩니다."
    )
    @PostMapping(value = "/recommendations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRecommend(
            @RequestBody RecommendRequest request,
            Authentication authentication) {

        User user = findUser(authentication.getName());

        UserContext context = UserContext.builder()
                .userId(user.getId())
                .tripId(request.getTripId())
                .currentPlanId(request.getCurrentPlanId())
                .currentLat(request.getCurrentLat())
                .currentLng(request.getCurrentLng())
                .radiusMinute(request.getRadiusMinute())
                .walk(request.isWalk())
                .selectedSpace(request.getSelectedSpace())
                .selectedType(request.getSelectedType())
                .keepOriginalCategory(request.isKeepOriginalCategory())
                .considerNextPlan(request.isConsiderNextPlan())
                .nextLat(request.getNextLat())
                .nextLng(request.getNextLng())
                .build();

        return recommendationService.streamRecommendations(context);
    }

    /**
     * POST /api/places/{placeId}/analyze
     * 특정 장소 AI 심층 분석 (DB place_id 기준)
     */
    @Operation(
        summary = "장소 심층 분석 트리거",
        description = "DB에 저장된 장소(placeId)에 대해 구글/네이버/인스타 리뷰를 수집하고 AI 분석을 실행합니다."
    )
    @PostMapping("/places/{placeId}/analyze")
    public ResponseEntity<String> analyze(@PathVariable Long placeId) {
        try {
            placeAnalysisService.processPlaceAnalysis(placeId);
            return ResponseEntity.ok("성공적으로 분석하여 DB에 반영했습니다! (ID: " + placeId + ")");
        } catch (IllegalArgumentException e) {
            log.warn("[Analyze] 장소 없음: placeId={}", placeId);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("[Analyze] 분석 전제 조건 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("분석 중 에러 발생: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Analyze] 분석 실패: placeId={}, error={}", placeId, e.getMessage());
            return ResponseEntity.internalServerError().body("분석 중 에러 발생: " + e.getMessage());
        }
    }

    /**
     * POST /api/plans/{planId}/replace
     * 일정(TripPlace)의 장소를 새로운 장소로 대체
     * 이름은 자동으로 "[새 장소명] (PLAN B)" 형식으로 변경
     */
    @Operation(
        summary = "일정 장소 대체 (PLAN B)",
        description = "특정 일정(planId = tripPlaceId)의 장소를 새로운 장소로 교체합니다. 이름에 (PLAN B) 표시가 추가됩니다."
    )
    @PostMapping("/plans/{planId}/replace")
    public ResponseEntity<ReplaceResponse> replace(
            @PathVariable Long planId,
            @RequestBody ReplaceRequest request,
            Authentication authentication) {

        tripService.replaceTripPlace(
                authentication.getName(),
                planId,
                request.getNewGooglePlaceId(),
                request.getNewPlaceName()
        );

        return ResponseEntity.ok(ReplaceResponse.builder()
                .tripPlaceId(planId)
                .googlePlaceId(request.getNewGooglePlaceId())
                .name("[" + request.getNewPlaceName() + "] (PLAN B)")
                .message("일정이 PLAN B로 대체되었습니다.")
                .build());
    }

    /**
     * PATCH /api/plans/{planId}/schedule
     * 장소는 그대로, 방문 시간(visitTime/endTime)과 메모만 수정
     */
    @Operation(
        summary = "일정 시간/메모 수정",
        description = "장소는 유지하면서 방문 시작 시간, 종료 시간, 메모만 수정합니다. 시간 겹침 시 400 에러를 반환합니다."
    )
    @PatchMapping("/plans/{planId}/schedule")
    public ResponseEntity<AddLocationResponse> updateSchedule(
            @PathVariable Long planId,
            @RequestBody UpdateScheduleRequest request,
            Authentication authentication) {

        AddLocationResponse response = tripService.updateTripPlaceSchedule(
                authentication.getName(), planId, request);
        return ResponseEntity.ok(response);
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }
}
