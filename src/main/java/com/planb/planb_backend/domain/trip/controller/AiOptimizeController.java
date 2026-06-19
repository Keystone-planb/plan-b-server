package com.planb.planb_backend.domain.trip.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

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

    private final WebClient aiServerWebClient;

    @Operation(
            summary = "장소 대안 최적화 스트리밍",
            description = "사용자가 선택한 장소의 대안을 탐색하고, 각 대안 교체 시 이후 모든 일정의 시간 변화를 SSE로 스트리밍합니다."
    )
    @PostMapping(
            value = "/{tripId}/days/{day}/places/{tripPlaceId}/optimize/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<ServerSentEvent<String>> optimizeStream(
            @PathVariable Long tripId,
            @PathVariable Integer day,
            @PathVariable Long tripPlaceId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return aiServerWebClient.post()
                .uri("/api/trips/{tripId}/days/{day}/places/{tripPlaceId}/optimize/stream",
                        tripId, day, tripPlaceId)
                .header("Authorization", authorization != null ? authorization : "")
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }
}
