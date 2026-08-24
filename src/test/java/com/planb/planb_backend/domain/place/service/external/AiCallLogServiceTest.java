package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.entity.AiCallLog;
import com.planb.planb_backend.domain.place.repository.AiCallLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * [관측성] AiCallLogService.getSummary() 의 지표 집계(폴백율/재질의 성공률/예상 비용)가
 * 정확히 계산되는지 확인하는 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class AiCallLogServiceTest {

    @Mock
    private AiCallLogRepository aiCallLogRepository;

    @Test
    @DisplayName("로그가 없으면 0으로 채워진 빈 요약을 반환")
    void getSummary_noLogs_returnsEmptySummary() {
        AiCallLogService service = new AiCallLogService(aiCallLogRepository);
        when(aiCallLogRepository.findByCreatedAtAfter(any())).thenReturn(List.of());

        AiCallLogService.AiMetricsSummary summary = service.getSummary(7);

        assertThat(summary.totalCalls()).isZero();
        assertThat(summary.fallbackRatePercent()).isZero();
        assertThat(summary.repairSuccessRatePercent()).isZero();
    }

    @Test
    @DisplayName("폴백/재질의 성공 비율과 예상 비용을 정확히 집계")
    void getSummary_withLogs_aggregatesCorrectly() {
        AiCallLogService service = new AiCallLogService(aiCallLogRepository);

        // 1) 정상 성공 2) 재질의로 복구 성공 3) 재질의했지만 결국 폴백
        AiCallLog success = buildLog(1000, 200, 1500, 0, false, false, false);
        AiCallLog repaired = buildLog(1200, 250, 3000, 1, true, true, false);
        AiCallLog failed = buildLog(900, 0, 2000, 3, true, false, true);

        when(aiCallLogRepository.findByCreatedAtAfter(any()))
                .thenReturn(List.of(success, repaired, failed));

        AiCallLogService.AiMetricsSummary summary = service.getSummary(7);

        assertThat(summary.totalCalls()).isEqualTo(3);
        assertThat(summary.fallbackRatePercent()).isEqualTo(33.33);       // 1/3
        assertThat(summary.repairAttemptRatePercent()).isEqualTo(66.67); // 2/3
        assertThat(summary.repairSuccessRatePercent()).isEqualTo(50.0);  // 재질의 2번 중 1번 성공
        assertThat(summary.totalRetries()).isEqualTo(4);                 // 0+1+3
        assertThat(summary.promptTokensSum()).isEqualTo(3100);
        assertThat(summary.completionTokensSum()).isEqualTo(450);
    }

    private AiCallLog buildLog(int promptTokens, int completionTokens, long latencyMs, int retryCount,
                                boolean repairAttempted, boolean repairSucceeded, boolean fallbackTriggered) {
        AiCallLog log = new AiCallLog();
        log.setPlaceName("테스트 장소");
        log.setModel("gpt-4o-mini");
        log.setPromptTokens(promptTokens);
        log.setCompletionTokens(completionTokens);
        log.setLatencyMs(latencyMs);
        log.setRetryCount(retryCount);
        log.setRepairAttempted(repairAttempted);
        log.setRepairSucceeded(repairSucceeded);
        log.setFallbackTriggered(fallbackTriggered);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }
}
