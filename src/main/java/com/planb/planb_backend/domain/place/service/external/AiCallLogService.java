package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.entity.AiCallLog;
import com.planb.planb_backend.domain.place.repository.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [관측성] OpenAI 호출 결과를 기록하고, 기간별 지표(지연시간/재시도/재질의/폴백/토큰/예상 비용)를 집계한다.
 * 기록 자체가 AI 분석 파이프라인을 막지 않도록 실패해도 예외를 흡수한다 (호출부에 영향 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallLogService {

    private final AiCallLogRepository aiCallLogRepository;

    // gpt-4o-mini 가격 (2026-08 기준, USD / 1M 토큰) — 가격 변동 시 여기만 수정
    private static final double INPUT_PRICE_PER_MILLION = 0.15;
    private static final double OUTPUT_PRICE_PER_MILLION = 0.60;

    public void record(String placeName, String model, Integer promptTokens, Integer completionTokens,
                        long latencyMs, int retryCount, boolean repairAttempted, boolean repairSucceeded,
                        boolean fallbackTriggered) {
        try {
            AiCallLog entry = new AiCallLog();
            entry.setPlaceName(placeName);
            entry.setModel(model);
            entry.setPromptTokens(promptTokens);
            entry.setCompletionTokens(completionTokens);
            entry.setLatencyMs(latencyMs);
            entry.setRetryCount(retryCount);
            entry.setRepairAttempted(repairAttempted);
            entry.setRepairSucceeded(repairSucceeded);
            entry.setFallbackTriggered(fallbackTriggered);
            entry.setCreatedAt(LocalDateTime.now());
            aiCallLogRepository.saveAndFlush(entry);
        } catch (Exception e) {
            log.warn("[AiCallLog] 기록 실패 — 분석 흐름에는 영향 없음: {}", e.getMessage());
        }
    }

    public AiMetricsSummary getSummary(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<AiCallLog> logs = aiCallLogRepository.findByCreatedAtAfter(since);

        if (logs.isEmpty()) {
            return AiMetricsSummary.empty(days);
        }

        int totalCalls = logs.size();
        double avgLatencyMs = logs.stream().mapToLong(AiCallLog::getLatencyMs).average().orElse(0);
        long fallbackCount = logs.stream().filter(AiCallLog::isFallbackTriggered).count();
        long repairAttemptedCount = logs.stream().filter(AiCallLog::isRepairAttempted).count();
        long repairSucceededCount = logs.stream().filter(AiCallLog::isRepairSucceeded).count();
        int totalRetries = logs.stream().mapToInt(AiCallLog::getRetryCount).sum();
        long promptTokensSum = logs.stream()
                .filter(l -> l.getPromptTokens() != null)
                .mapToLong(AiCallLog::getPromptTokens).sum();
        long completionTokensSum = logs.stream()
                .filter(l -> l.getCompletionTokens() != null)
                .mapToLong(AiCallLog::getCompletionTokens).sum();

        double estimatedCostUsd = (promptTokensSum / 1_000_000.0) * INPUT_PRICE_PER_MILLION
                + (completionTokensSum / 1_000_000.0) * OUTPUT_PRICE_PER_MILLION;

        return new AiMetricsSummary(
                days,
                totalCalls,
                Math.round(avgLatencyMs),
                round2(fallbackCount * 100.0 / totalCalls),
                round2(repairAttemptedCount * 100.0 / totalCalls),
                repairAttemptedCount == 0 ? 0.0 : round2(repairSucceededCount * 100.0 / repairAttemptedCount),
                totalRetries,
                promptTokensSum,
                completionTokensSum,
                round2(estimatedCostUsd)
        );
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record AiMetricsSummary(
            int periodDays,
            int totalCalls,
            long avgLatencyMs,
            double fallbackRatePercent,
            double repairAttemptRatePercent,
            double repairSuccessRatePercent,
            int totalRetries,
            long promptTokensSum,
            long completionTokensSum,
            double estimatedCostUsd
    ) {
        static AiMetricsSummary empty(int days) {
            return new AiMetricsSummary(days, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
