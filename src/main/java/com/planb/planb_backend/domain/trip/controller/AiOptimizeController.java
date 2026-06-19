package com.planb.planb_backend.domain.trip.controller;

import com.planb.planb_backend.domain.trip.dto.OptimizeStreamRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 서버(Python FastAPI) 장소 대안 최적화 SSE 프록시 컨트롤러
 *
 * 흐름: 앱 → Spring Boot → AI 서버 → SSE 스트리밍 역방향 전달
 * - 사용자가 특정 장소의 대안을 요청하면 AI 서버가 Google Places로 후보를 검색하고
 *   각 후보 교체 시 전체 일정 이동 시간 변화를 SSE로 스트리밍
 *
 * SSE 이벤트 순서:
 * optimize_start → alternative_found (최대 5회) → optimize_done
 */
@Tag(name = "AI 일정 최적화", description = "장소 대안 탐색 및 동선 최적화 API")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class AiOptimizeController {

    // 기존 코드 유지
    private final WebClient aiServerWebClient;

    @Operation(
            summary = "장소 대안 최적화 스트리밍",
            description = "사용자가 선택한 장소의 대안을 탐색하고, 각 대안 교체 시 이후 모든 일정의 시간 변화를 SSE로 스트리밍합니다. " +
                          "재조회 시 excludedPlaceIds로 이미 본 장소를 제외할 수 있습니다."
    )
    @PostMapping(
            value = "/{tripId}/days/{day}/places/{tripPlaceId}/optimize/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<ServerSentEvent<String>> optimizeStream(
            // 기존 코드 유지
            @PathVariable Long tripId,
            @PathVariable Integer day,
            @PathVariable Long tripPlaceId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            // 신규 추가: 중복 방지 로직 — null/빈 배열이면 기존 동작과 100% 동일
            @RequestBody(required = false) OptimizeStreamRequest body
    ) {
        // 신규 추가: 중복 방지 로직 — excludedPlaceIds 추출 (없으면 빈 리스트)
        List<String> excludedList = (body != null) ? body.getExcludedPlaceIds() : List.of();
        Set<String> excludedSet = Set.copyOf(excludedList);

        // 신규 추가: AI 서버로 전달할 body 구성
        // excludedPlaceIds가 없으면 빈 Map → AI 서버 기존 동작 유지
        Map<String, Object> aiRequestBody = new HashMap<>();
        if (!excludedList.isEmpty()) {
            aiRequestBody.put("excludedPlaceIds", excludedList);
        }

        // 기존 코드 유지 — WebClient 요청 빌드
        WebClient.RequestBodySpec requestSpec = aiServerWebClient.post()
                .uri("/api/trips/{tripId}/days/{day}/places/{tripPlaceId}/optimize/stream",
                        tripId, day, tripPlaceId)
                .header("Authorization", authorization != null ? authorization : "")
                .contentType(MediaType.APPLICATION_JSON);

        // 기존 코드 유지 — AI 서버 호출 (body 유무에 따라 분기)
        Flux<String> rawFlux = aiRequestBody.isEmpty()
                ? requestSpec.retrieve().bodyToFlux(String.class)
                : requestSpec.bodyValue(aiRequestBody).retrieve().bodyToFlux(String.class);

        return rawFlux
                // 신규 추가: 중복 방지 로직 — AI 서버 미업데이트 시 Spring Boot에서 2차 필터링
                .filter(chunk -> isNotExcluded(chunk, excludedSet))
                // 기존 코드 유지 — SSE 이벤트로 래핑
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * 신규 추가: 중복 방지 로직
     *
     * SSE chunk가 alternative_found 이벤트이고, chunk 안에 제외 대상 placeId가 포함되어 있으면
     * false를 반환해서 스트림에서 걸러냅니다.
     *
     * excludedSet이 비어있으면 항상 true → 기존 동작과 완전히 동일
     */
    private boolean isNotExcluded(String chunk, Set<String> excludedSet) {
        if (excludedSet.isEmpty()) return true;                   // 제외 목록 없음 → 통과
        if (!chunk.contains("alternative_found")) return true;   // 다른 이벤트 → 통과
        return excludedSet.stream().noneMatch(chunk::contains);  // placeId 포함 시 제거
    }
}
