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
 * AI 서버(Python FastAPI) SSE 스트리밍 프록시 컨트롤러
 *
 * 흐름: 앱 → Spring Boot → AI 서버 → SSE 스트리밍 역방향 전달
 * - AI 서버가 악천후 감지 시 여행 일정을 자동 복구하고 진행 과정을 SSE로 전송
 * - Spring Boot는 AI 서버의 SSE를 그대로 앱으로 중계 (파싱/가공 없음)
 *
 * SSE 이벤트 순서:
 * recovery_start → place_changed (N회) → time_adjusted (N회) → recovery_done
 */
@Tag(name = "AI 일정 복구", description = "악천후 감지 시 AI 서버를 통한 여행 일정 자동 복구 API")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class AiRecoveryController {

    private final WebClient aiServerWebClient;

    @Operation(
            summary = "AI 일정 복구 스트리밍",
            description = "악천후 감지 시 AI 서버가 해당 일차의 야외 장소를 실내 대안으로 자동 교체합니다. " +
                          "응답은 SSE(Server-Sent Events) 스트리밍 방식입니다."
    )
    @PostMapping(
            value = "/{tripId}/days/{day}/recovery/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<ServerSentEvent<String>> recoveryStream(
            @PathVariable Long tripId,
            @PathVariable Integer day,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return aiServerWebClient.post()
                .uri("/api/trips/{tripId}/days/{day}/recovery/stream", tripId, day)
                .header("Authorization", authorization != null ? authorization : "")
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }
}
